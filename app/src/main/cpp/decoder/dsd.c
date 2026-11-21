// DSF and DSDIFF (DFF) reader with three outputs:
//  - Native: raw DSD for a DAC's native DSD mode (UAC2 raw-data alt setting, Linux DSD_U32_BE):
//    per frame and channel 4 bytes, oldest byte first, oldest bit in each byte's MSB, at DSD
//    rate / 32. Handed to Media3 as 16-bit PCM with twice the channels (2 samples = 4 bytes per
//    DSD channel), because Media3 passes 16-bit PCM through untouched; the USB driver copies the
//    bytes straight into the DAC's 32-bit slots. Stereo only. Bit-exact.
//  - DoP (DSD over PCM, v1.1): 16 DSD bits per channel in each 24-bit sample, under an alternating
//    0x05 / 0xFA marker byte, at DSD rate / 16. A DoP-capable DAC (the KA17 up to DoP256) plays
//    it as native DSD. Bit-exact; nothing may alter the samples on the way.
//  - PCM: 32-bit float at 176.4 kHz (192 kHz for 48k-family DSD), via a linear-phase FIR
//    low-pass (Blackman-windowed sinc, -6 dB at 40 kHz) evaluated with per-byte lookup tables,
//    window centred on each output frame, file edges mirrored (gapless joins stay clean).
//    Used for the phone speaker / Android output, DSD512, and so plugins get real audio.
// DST-compressed DFF is not supported.

#include "decoder.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define DSD_SILENCE 0x69  // balanced idle pattern
#define PCM_RATE_441 176400
#define PCM_RATE_48 192000
#define MAX_DOP_RATE 12288000  // DoP256 (11.2896 / 12.288 MHz)
#define MAX_CH 2

typedef struct {
    Decoder base;
    int fd;
    int is_dff;
    int lsb_first;
    int channels;
    uint32_t dsd_rate;
    int64_t data_start;
    int64_t bytes_per_ch;
    int block;  // DSF: bytes per channel block (4096)

    int dop;
    int native;
    int64_t out_frame;

    // PCM conversion
    int step;  // DSD bytes per output frame
    int taps_bytes;  // filter length in bytes (taps / 8)
    float *tables;  // taps_bytes * 256

    // scratch
    uint8_t *buf[MAX_CH];
    size_t buf_cap;
    uint8_t *raw;
    size_t raw_cap;
    uint8_t bitrev[256];
} Dsd;

static uint32_t be32(const uint8_t *p) { return ((uint32_t) p[0] << 24) | ((uint32_t) p[1] << 16) | ((uint32_t) p[2] << 8) | p[3]; }
static uint64_t be64(const uint8_t *p) { return ((uint64_t) be32(p) << 32) | be32(p + 4); }
static uint32_t le32(const uint8_t *p) { return p[0] | ((uint32_t) p[1] << 8) | ((uint32_t) p[2] << 16) | ((uint32_t) p[3] << 24); }
static uint64_t le64(const uint8_t *p) { return le32(p) | ((uint64_t) le32(p + 4) << 32); }

static int read_at(int fd, void *dst, size_t n, int64_t pos) {
    uint8_t *d = dst;
    while (n > 0) {
        ssize_t r = pread(fd, d, n, (off_t) pos);
        if (r <= 0) return -1;
        d += r;
        n -= (size_t) r;
        pos += r;
    }
    return 0;
}

static int ensure(uint8_t **p, size_t *cap, size_t need) {
    if (*cap >= need) return 0;
    uint8_t *n = realloc(*p, need);
    if (!n) return -1;
    *p = n;
    *cap = need;
    return 0;
}

/**
 * Fills d->buf[c][0..n) with DSD bytes [start, start+n) of each channel, MSB = oldest bit.
 * Outside the file: DSD idle, or with [mirror] the stream reflected at the file's edge
 * (byte -1-m = bit-reversed byte m; byte N+m = bit-reversed byte N-1-m), which keeps the PCM
 * filter's edge windows at the music's own level instead of pulling them towards silence. That's
 * what makes gapless DSD -> PCM joins clean.
 */
static int fetch_ex(Dsd *d, int64_t start, int n, int mirror) {
    size_t need = (size_t) n;
    if (d->buf_cap < need) {
        for (int c = 0; c < d->channels; c++) {
            uint8_t *nb = realloc(d->buf[c], need);
            if (!nb) return -1;
            d->buf[c] = nb;
        }
        d->buf_cap = need;
    }
    // Outside the file: idle pattern (filter warm-up before the start, padding after the end).
    int64_t lo = start < 0 ? 0 : start;
    int64_t hi = start + n > d->bytes_per_ch ? d->bytes_per_ch : start + n;
    for (int c = 0; c < d->channels; c++) {
        if (lo > start) memset(d->buf[c], DSD_SILENCE, (size_t) (lo - start));
        if (hi < start + n) {
            int64_t from = hi > start ? hi : start;
            memset(d->buf[c] + (from - start), DSD_SILENCE, (size_t) (start + n - from));
        }
    }
    if (hi <= lo) return 0;

    if (d->is_dff) {  // interleaved per byte: c0 c1 c0 c1 ...
        size_t len = (size_t) (hi - lo) * (size_t) d->channels;
        if (ensure(&d->raw, &d->raw_cap, len)) return -1;
        if (read_at(d->fd, d->raw, len, d->data_start + lo * d->channels)) return -1;
        for (int64_t i = 0; i < hi - lo; i++)
            for (int c = 0; c < d->channels; c++) d->buf[c][lo - start + i] = d->raw[i * d->channels + c];
    } else {  // DSF: blocks of [block] bytes per channel: c0 block, c1 block, ...
        int64_t i = lo;
        while (i < hi) {
            int64_t blk = i / d->block;
            int off = (int) (i % d->block);
            int run = d->block - off;
            if (run > hi - i) run = (int) (hi - i);
            for (int c = 0; c < d->channels; c++) {
                int64_t pos = d->data_start + (blk * d->channels + c) * d->block + off;
                if (read_at(d->fd, d->buf[c] + (i - start), (size_t) run, pos)) return -1;
            }
            i += run;
        }
    }
    if (d->lsb_first) {
        for (int c = 0; c < d->channels; c++)
            for (int64_t i = lo; i < hi; i++) d->buf[c][i - start] = d->bitrev[d->buf[c][i - start]];
    }
    if (mirror) {
        int64_t N = d->bytes_per_ch;
        for (int c = 0; c < d->channels; c++) {
            uint8_t *b = d->buf[c];
            for (int64_t i = start; i < start + n; i++) {
                if (i >= 0 && i < N) continue;
                int64_t src = i < 0 ? -1 - i : 2 * N - 1 - i;
                // Only from bytes loaded above (edges are at most half a filter long); else idle.
                if (src >= lo && src < hi) b[i - start] = d->bitrev[b[src - start]];
            }
        }
    }
    return 0;
}

static int fetch(Dsd *d, int64_t start, int n) { return fetch_ex(d, start, n, 0); }

static int dop_read(Decoder *base, uint8_t *out, int max_frames) {
    Dsd *d = (Dsd *) base;
    int64_t left = d->base.info.total_frames - d->out_frame;
    int frames = left < max_frames ? (int) left : max_frames;
    if (frames <= 0) return 0;
    if (fetch(d, d->out_frame * 2, frames * 2)) return -1;
    uint8_t *p = out;
    for (int f = 0; f < frames; f++) {
        uint8_t marker = ((d->out_frame + f) & 1) ? 0xFA : 0x05;
        for (int c = 0; c < d->channels; c++) {
            p[0] = d->buf[c][2 * f + 1];  // newer byte, low
            p[1] = d->buf[c][2 * f];      // older byte, middle
            p[2] = marker;
            p += 3;
        }
    }
    d->out_frame += frames;
    return frames;
}

static int native_read(Decoder *base, uint8_t *out, int max_frames) {
    Dsd *d = (Dsd *) base;
    int64_t left = d->base.info.total_frames - d->out_frame;
    int frames = left < max_frames ? (int) left : max_frames;
    if (frames <= 0) return 0;
    if (fetch(d, d->out_frame * 4, frames * 4)) return -1;
    uint8_t *p = out;
    for (int f = 0; f < frames; f++) {
        for (int c = 0; c < d->channels; c++) {
            memcpy(p, d->buf[c] + 4 * f, 4);  // oldest byte first, MSB = oldest bit
            p += 4;
        }
    }
    d->out_frame += frames;
    return frames;
}

static int pcm_read(Decoder *base, uint8_t *out, int max_frames) {
    Dsd *d = (Dsd *) base;
    int64_t left = d->base.info.total_frames - d->out_frame;
    int frames = left < max_frames ? (int) left : max_frames;
    if (frames <= 0) return 0;
    const int L = d->taps_bytes, R = d->step;
    // Output j's filter window is centred on its own R bytes: bytes [j*R + (R-L)/2, +L). No
    // half-filter delay, and each track's PCM covers exactly its own DSD (edges mirrored).
    int64_t start = d->out_frame * R + (R - L) / 2;
    if (fetch_ex(d, start, (frames - 1) * R + L, 1)) return -1;
    float *o = (float *) out;
    for (int f = 0; f < frames; f++) {
        for (int c = 0; c < d->channels; c++) {
            const uint8_t *newest = d->buf[c] + f * R + L - 1;
            const float *t = d->tables;
            float acc = 0;
            for (int k = 0; k < L; k++, t += 256) acc += t[newest[-k]];
            o[f * d->channels + c] = acc;
        }
    }
    d->out_frame += frames;
    return frames;
}

static int dsd_seek(Decoder *base, int64_t frame) {
    Dsd *d = (Dsd *) base;
    if (frame < 0) frame = 0;
    if (frame > d->base.info.total_frames) frame = d->base.info.total_frames;
    d->out_frame = frame;
    return 0;
}

static void dsd_close(Decoder *base) {
    Dsd *d = (Dsd *) base;
    for (int c = 0; c < MAX_CH; c++) free(d->buf[c]);
    free(d->raw);
    free(d->tables);
    close(d->fd);
    free(d);
}

/** Lookup tables for the FIR: table[k][v] = sum of the 8 taps byte k covers, signed by v's bits. */
static int build_filter(Dsd *d) {
    int mult = (int) (d->dsd_rate / (d->dsd_rate % 44100 == 0 ? 2822400u : 3072000u));
    if (mult < 1) mult = 1;
    if (mult > 4) mult = 4;  // DSD512: wider transition band, still far above the audio band
    d->taps_bytes = 48 * mult;
    int n = d->taps_bytes * 8;
    double *h = malloc(sizeof(double) * (size_t) n);
    d->tables = malloc(sizeof(float) * 256 * (size_t) d->taps_bytes);
    if (!h || !d->tables) {
        free(h);
        return -1;
    }
    double fc = 40000.0 / d->dsd_rate, sum = 0, mid = (n - 1) / 2.0;
    for (int t = 0; t < n; t++) {
        double x = t - mid;
        double s = x == 0 ? 2 * fc : sin(2 * M_PI * fc * x) / (M_PI * x);
        double w = 0.42 - 0.5 * cos(2 * M_PI * t / (n - 1)) + 0.08 * cos(4 * M_PI * t / (n - 1));
        h[t] = s * w;
        sum += h[t];
    }
    for (int t = 0; t < n; t++) h[t] /= sum;  // unity DC gain
    // Byte k = 0 is the newest; within a byte the LSB is the newest bit.
    for (int k = 0; k < d->taps_bytes; k++) {
        for (int v = 0; v < 256; v++) {
            double acc = 0;
            for (int p = 0; p < 8; p++) acc += h[8 * k + p] * (((v >> p) & 1) ? 1.0 : -1.0);
            d->tables[k * 256 + v] = (float) acc;
        }
    }
    free(h);
    return 0;
}

static int parse_dsf(Dsd *d, char *err, int err_len) {
    uint8_t h[92];
    if (read_at(d->fd, h, sizeof h, 0)) return -1;
    if (memcmp(h + 28, "fmt ", 4) || le32(h + 44) != 0) {
        snprintf(err, err_len, "DSF: unsupported fmt");
        return -1;
    }
    d->channels = (int) le32(h + 52);
    d->dsd_rate = le32(h + 56);
    uint32_t bits = le32(h + 60);
    uint64_t samples = le64(h + 64);
    d->block = (int) le32(h + 72);
    uint64_t fmt_size = le64(h + 32);
    int64_t data_hdr = 28 + (int64_t) fmt_size;
    uint8_t dh[12];
    if (read_at(d->fd, dh, sizeof dh, data_hdr) || memcmp(dh, "data", 4)) {
        snprintf(err, err_len, "DSF: no data chunk");
        return -1;
    }
    d->data_start = data_hdr + 12;
    d->lsb_first = bits == 1;
    d->bytes_per_ch = (int64_t) (samples / 8);
    if (d->block <= 0 || (bits != 1 && bits != 8)) {
        snprintf(err, err_len, "DSF: bad block/bit settings");
        return -1;
    }
    return 0;
}

static int parse_dff(Dsd *d, char *err, int err_len) {
    uint8_t h[16];
    if (read_at(d->fd, h, sizeof h, 0) || memcmp(h + 12, "DSD ", 4)) return -1;
    int64_t end = 12 + (int64_t) be64(h + 4);
    int64_t pos = 16;
    int have_data = 0;
    while (pos + 12 <= end && !have_data) {
        uint8_t ch[12];
        if (read_at(d->fd, ch, sizeof ch, pos)) break;
        int64_t size = (int64_t) be64(ch + 4);
        int64_t body = pos + 12;
        if (!memcmp(ch, "PROP", 4)) {
            int64_t p = body + 4;  // skip "SND "
            while (p + 12 <= body + size) {
                uint8_t sc[16];
                if (read_at(d->fd, sc, 16, p)) break;
                int64_t ssize = (int64_t) be64(sc + 4);
                if (!memcmp(sc, "FS  ", 4)) d->dsd_rate = be32(sc + 12);
                else if (!memcmp(sc, "CHNL", 4)) d->channels = (sc[12] << 8) | sc[13];
                else if (!memcmp(sc, "CMPR", 4) && memcmp(sc + 12, "DSD ", 4)) {
                    snprintf(err, err_len, "DFF: DST-compressed DSD is not supported");
                    return -1;
                }
                p += 12 + ssize + (ssize & 1);
            }
        } else if (!memcmp(ch, "DSD ", 4)) {
            d->data_start = body;
            d->bytes_per_ch = d->channels > 0 ? size / d->channels : 0;
            have_data = 1;
        } else if (!memcmp(ch, "DST ", 4)) {
            snprintf(err, err_len, "DFF: DST-compressed DSD is not supported");
            return -1;
        }
        pos = body + size + (size & 1);
    }
    if (!have_data) {
        snprintf(err, err_len, "DFF: no DSD data");
        return -1;
    }
    d->is_dff = 1;
    d->lsb_first = 0;
    return 0;
}

Decoder *dsd_open(int fd, int dop, uint32_t native_max_rate, char *err, int err_len) {
    uint8_t magic[4];
    if (read_at(fd, magic, 4, 0)) return NULL;
    int is_dsf = !memcmp(magic, "DSD ", 4), is_dff = !memcmp(magic, "FRM8", 4);
    if (!is_dsf && !is_dff) return NULL;

    Dsd *d = calloc(1, sizeof(Dsd));
    if (!d) return NULL;
    d->fd = dup(fd);
    for (int v = 0; v < 256; v++) {
        int r = 0;
        for (int b = 0; b < 8; b++) if (v & (1 << b)) r |= 0x80 >> b;
        d->bitrev[v] = (uint8_t) r;
    }
    int rc = is_dsf ? parse_dsf(d, err, err_len) : parse_dff(d, err, err_len);
    if (rc == 0 && (d->channels < 1 || d->channels > MAX_CH)) {
        snprintf(err, err_len, "DSD: %d channels not supported (1-2)", d->channels);
        rc = -1;
    }
    if (rc == 0 && (d->dsd_rate < 2822400 || (d->dsd_rate % 44100 && d->dsd_rate % 48000))) {
        snprintf(err, err_len, "DSD: unsupported rate %u", d->dsd_rate);
        rc = -1;
    }
    if (rc) {
        if (!err[0]) snprintf(err, err_len, "DSD: unreadable header");
        dsd_close(&d->base);
        return NULL;
    }

    int multiple = (int) (d->dsd_rate / (d->dsd_rate % 44100 == 0 ? 2822400u : 3072000u));
    DecInfo *in = &d->base.info;
    in->channels = d->channels;
    in->is_dsd = 1;
    d->native = native_max_rate > 0 && d->dsd_rate <= native_max_rate && d->channels == 2;
    d->dop = !d->native && dop && d->dsd_rate <= MAX_DOP_RATE;
    if (d->native) {
        in->is_native = 1;
        in->format = DEC_S16;
        in->channels = 2 * d->channels;  // two 16-bit "samples" carry one 32-bit DSD word
        in->sample_rate = (int) (d->dsd_rate / 32);
        in->total_frames = d->bytes_per_ch / 4;
        d->base.read = native_read;
        snprintf(in->desc, sizeof in->desc, "%s DSD%d → native DSD %.4f MHz (bit-perfect)",
                 d->is_dff ? "DFF" : "DSF", 64 * multiple, d->dsd_rate / 1e6);
    } else if (d->dop) {
        in->is_dop = 1;
        in->format = DEC_S24;
        in->sample_rate = (int) (d->dsd_rate / 16);
        // Even frame count (last frame padded with DSD idle): every track then starts with marker
        // 0x05 right after one that ended with 0xFA, so gapless DoP never repeats a marker (a
        // repeat can make the DAC drop out of DSD mode for a moment: a click between tracks).
        in->total_frames = ((d->bytes_per_ch + 1) / 2 + 1) & ~(int64_t) 1;
        d->base.read = dop_read;
        snprintf(in->desc, sizeof in->desc, "%s DSD%d → DoP %.1f kHz (native DSD on the DAC)",
                 d->is_dff ? "DFF" : "DSF", 64 * multiple, in->sample_rate / 1000.0);
    } else {
        int pcm_rate = d->dsd_rate % 44100 == 0 ? PCM_RATE_441 : PCM_RATE_48;
        d->step = (int) (d->dsd_rate / pcm_rate / 8);
        if (build_filter(d)) {
            dsd_close(&d->base);
            return NULL;
        }
        in->format = DEC_F32;
        in->sample_rate = pcm_rate;
        in->total_frames = d->bytes_per_ch / d->step;
        d->base.read = pcm_read;
        snprintf(in->desc, sizeof in->desc, "%s DSD%d → PCM %.1f kHz", d->is_dff ? "DFF" : "DSF",
                 64 * multiple, pcm_rate / 1000.0);
    }
    d->base.seek = dsd_seek;
    d->base.close = dsd_close;
    return &d->base;
}

int dec_sample_bytes(int format) {
    switch (format) {
        case DEC_S16: return 2;
        case DEC_S24: return 3;
        default: return 4;
    }
}
