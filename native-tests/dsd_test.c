#include "decoder.h"
#include <assert.h>
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifndef JOIN_LIMIT
#define JOIN_LIMIT 0.05
#endif
#define RATE 2822400
#define SECONDS 1
#define NBITS (RATE * SECONDS)
static uint8_t bits[2][NBITS / 8];  // MSB-first, per channel

static void put32le(FILE *f, uint32_t v) { for (int i = 0; i < 4; i++) fputc((v >> (8 * i)) & 0xFF, f); }
static void put64le(FILE *f, uint64_t v) { for (int i = 0; i < 8; i++) fputc((v >> (8 * i)) & 0xFF, f); }
static void put32be(FILE *f, uint32_t v) { for (int i = 3; i >= 0; i--) fputc((v >> (8 * i)) & 0xFF, f); }
static void put64be(FILE *f, uint64_t v) { for (int i = 7; i >= 0; i--) fputc((v >> (8 * i)) & 0xFF, f); }
static uint8_t rev(uint8_t v) { uint8_t r = 0; for (int b = 0; b < 8; b++) if (v & (1 << b)) r |= 0x80 >> b; return r; }

static void modulate(void) {  // 2nd-order sigma-delta; ch0 1 kHz @ 0.5, ch1 3 kHz @ 0.25
    double fr[2] = {1000, 3000}, amp[2] = {0.5, 0.25};
    for (int c = 0; c < 2; c++) {
        double i1 = 0, i2 = 0, y = 0;
        for (int n = 0; n < NBITS; n++) {
            double x = amp[c] * sin(2 * M_PI * fr[c] * n / RATE);
            i1 += x - y; i2 += i1 - y;
            y = i2 >= 0 ? 1 : -1;
            if (y > 0) bits[c][n / 8] |= 0x80 >> (n % 8);
        }
    }
}

static void write_dsf_rate(const char *path, uint32_t rate);
static void write_dsf(const char *path) { write_dsf_rate(path, RATE); }
static void write_dsf_rate(const char *path, uint32_t rate) {
    FILE *f = fopen(path, "wb");
    int B = 4096, bpc = NBITS / 8, blocks = (bpc + B - 1) / B;
    uint64_t data = (uint64_t) blocks * B * 2;
    fwrite("DSD ", 1, 4, f); put64le(f, 28); put64le(f, 92 + data); put64le(f, 0);
    fwrite("fmt ", 1, 4, f); put64le(f, 52); put32le(f, 1); put32le(f, 0); put32le(f, 2); put32le(f, 2);
    put32le(f, rate); put32le(f, 1); put64le(f, NBITS); put32le(f, B); put32le(f, 0);
    fwrite("data", 1, 4, f); put64le(f, 12 + data);
    for (int b = 0; b < blocks; b++)
        for (int c = 0; c < 2; c++)
            for (int i = 0; i < B; i++) { int idx = b * B + i; fputc(idx < bpc ? rev(bits[c][idx]) : 0, f); }
    fclose(f);
}

static void write_dff_range(const char *path, uint64_t from, uint64_t bpc);
static void write_dff_n(const char *path, uint64_t bpc) { write_dff_range(path, 0, bpc); }
static void write_dff_range(const char *path, uint64_t from, uint64_t bpc) {
    FILE *f = fopen(path, "wb");
    uint64_t dsd = bpc * 2;
    uint64_t prop = 4 + (12 + 4) + (12 + 2 + 8) + (12 + 4 + 1 + 1);  // SND + FS + CHNL + CMPR(odd->pad)
    uint64_t total = 4 + (12 + 4) + (12 + prop) + (12 + dsd);
    fwrite("FRM8", 1, 4, f); put64be(f, total); fwrite("DSD ", 1, 4, f);
    fwrite("FVER", 1, 4, f); put64be(f, 4); put32be(f, 0x01050000);
    fwrite("PROP", 1, 4, f); put64be(f, prop); fwrite("SND ", 1, 4, f);
    fwrite("FS  ", 1, 4, f); put64be(f, 4); put32be(f, RATE);
    fwrite("CHNL", 1, 4, f); put64be(f, 10); fputc(0, f); fputc(2, f); fwrite("SLFTSRGT", 1, 8, f);
    fwrite("CMPR", 1, 4, f); put64be(f, 5); fwrite("DSD ", 1, 4, f); fputc(0, f); fputc(0, f); // count + pad
    fwrite("DSD ", 1, 4, f); put64be(f, dsd);
    for (uint64_t i = from; i < from + bpc; i++) { fputc(bits[0][i], f); fputc(bits[1][i], f); }
    fclose(f);
}
static void write_dff(const char *path) { write_dff_n(path, NBITS / 8); }

static float *pcm_of(const char *path, int *frames) {
    char err[128] = "";
    int fd = open(path, O_RDONLY);
    Decoder *d = dsd_open(fd, 0, 0, err, sizeof err);
    assert(d);
    int n = (int) d->info.total_frames, got = 0, r;
    float *p = malloc(sizeof(float) * 2 * n);
    while ((r = d->read(d, (uint8_t *) (p + 2 * got), 1000)) > 0) got += r;
    assert(got == n);
    d->close(d);
    close(fd);
    *frames = n;
    return p;
}

/**
 * Gapless DSD -> PCM: one continuous stream split into two files. Converting each file on its
 * own and playing them back to back must match converting the whole stream, also at the join.
 */
static void check_pcm_join(void) {
    uint64_t total = NBITS / 8, half = 100000;  // bytes per channel; split mid-tone
    write_dff_range("/tmp/allmeyn-native-tests/whole.dff", 0, total);
    write_dff_range("/tmp/allmeyn-native-tests/a.dff", 0, half);
    write_dff_range("/tmp/allmeyn-native-tests/b.dff", half, total - half);
    int nw, na, nb;
    float *w = pcm_of("/tmp/allmeyn-native-tests/whole.dff", &nw);
    float *a = pcm_of("/tmp/allmeyn-native-tests/a.dff", &na);
    float *b = pcm_of("/tmp/allmeyn-native-tests/b.dff", &nb);
    assert(na + nb == nw);
    double worst = 0;
    for (int j = na - 40; j < na + 40; j++) {
        float v = j < na ? a[2 * j] : b[2 * (j - na)];
        double dd = fabs(v - w[2 * j]);
        if (dd > worst) worst = dd;
    }
    double interior = 0;
    for (int j = 1000; j < na - 1000; j++) { double dd = fabs(a[2 * j] - w[2 * j]); if (dd > interior) interior = dd; }
    printf("DSD->PCM gapless join: max error at the join %.4f (signal 0.5), elsewhere %.1e\n", worst, interior);
    fflush(stdout);
    assert(interior < 1e-6);
    assert(worst < JOIN_LIMIT);
    free(w); free(a); free(b);
}

/** DoP of a track whose length isn't a multiple of 4 bytes: even frame count, idle padding, markers alternate. */
static void check_dop_padding(void) {
    const char *path = "/tmp/allmeyn-native-tests/odd.dff";
    uint64_t bpc = 1001;  // 1001 bytes per channel -> 500.5 DoP frames -> 502 after padding
    write_dff_n(path, bpc);
    char err[128] = "";
    int fd = open(path, O_RDONLY);
    Decoder *d = dsd_open(fd, 1, 0, err, sizeof err);
    assert(d && d->info.is_dop);
    assert(d->info.total_frames == 502);
    uint8_t buf[6 * 502];
    int got = 0, r;
    while ((r = d->read(d, buf + 6 * got, 100)) > 0) got += r;
    assert(got == 502);
    assert(buf[6 * 501 + 2] == 0xFA);  // last marker, so the next track's 0x05 alternates
    // frame 500: older byte = last real byte 1000, newer byte = idle; frame 501: idle
    for (int c = 0; c < 2; c++) {
        const uint8_t *p = buf + 6 * 500 + 3 * c;
        assert(p[1] == bits[c][1000] && p[0] == 0x69);
        const uint8_t *q = buf + 6 * 501 + 3 * c;
        assert(q[0] == 0x69 && q[1] == 0x69);
    }
    d->close(d);
    close(fd);
    puts("DoP gapless padding: even frame count, ends on 0xFA, idle-padded");
}

#include <complex.h>
static void fft(double complex *a, int n) {
    for (int i = 1, j = 0; i < n; i++) { int bit = n >> 1; for (; j & bit; bit >>= 1) j ^= bit; j ^= bit; if (i < j) { double complex t = a[i]; a[i] = a[j]; a[j] = t; } }
    for (int len = 2; len <= n; len <<= 1) { double complex w = cexp(-2 * M_PI * I / len);
        for (int i = 0; i < n; i += len) { double complex u = 1; for (int k = 0; k < len / 2; k++) { double complex x = a[i + k], y = a[i + k + len / 2] * u; a[i + k] = x + y; a[i + k + len / 2] = x - y; u *= w; } } }
}
/** Error power below 20 kHz relative to the tone (Hann-windowed), in dB. */
static double inband_err_db(const double *e, int n, double rate, double sig_amp) {
    double complex *a = malloc(sizeof(double complex) * n);
    for (int i = 0; i < n; i++) a[i] = e[i] * (0.5 - 0.5 * cos(2 * M_PI * i / n));
    fft(a, n);
    int top = (int) (20000.0 * n / rate);
    double p = 0; for (int k = 1; k <= top; k++) p += 2 * creal(a[k] * conj(a[k]));
    free(a);
    double enbw = 0.375 * n;  // Hann window power normalisation
    double err_rms2 = p / (n * enbw);
    return 10 * log10(err_rms2 / (sig_amp * sig_amp / 2));
}
static double tone_amp(const float *x, int n, int ch, int stride, double fr, double rate) {
    double s = 0, c = 0;
    for (int i = 0; i < n; i++) { s += x[i * stride + ch] * sin(2 * M_PI * fr * i / rate); c += x[i * stride + ch] * cos(2 * M_PI * fr * i / rate); }
    return 2 * sqrt(s * s + c * c) / n;
}

static void check_file(const char *path, const char *name) {
    char err[128] = "";
    int fd = open(path, O_RDONLY);
    // PCM path
    Decoder *d = dsd_open(fd, 0, 0, err, sizeof err);
    assert(d && "open pcm");
    assert(d->info.sample_rate == 176400 && d->info.channels == 2 && d->info.format == DEC_F32);
    assert(d->info.total_frames == NBITS / 16);
    int n = (int) d->info.total_frames;
    float *pcm = malloc(sizeof(float) * 2 * n);
    int got = 0, r;
    while ((r = d->read(d, (uint8_t *) (pcm + 2 * got), 1000)) > 0) got += r;
    assert(got == n);
    // skip the filter's warm-up edges
    int a = 2000, len = n - 4000;
    double a0 = tone_amp(pcm + 2 * a, len, 0, 2, 1000, 176400), a0x = tone_amp(pcm + 2 * a, len, 0, 2, 3000, 176400);
    double a1 = tone_amp(pcm + 2 * a, len, 1, 2, 3000, 176400);
    int fn = 131072;
    double *err_sig = malloc(sizeof(double) * fn);
    // Output j's window is bytes [2j-23, 2j+25): centred on bit 16j + 7.5.
    for (int i = 0; i < fn; i++) { double tt = ((a + i) * 16.0 + 7.5) / RATE; err_sig[i] = pcm[2 * (a + i)] - 0.5 * sin(2 * M_PI * 1000 * tt); }
    double inband = inband_err_db(err_sig, fn, 176400, 0.5);
    free(err_sig);
    printf("%s PCM: ch0 1k=%.4f (want 0.5) 3k leak=%.5f · ch1 3k=%.4f (want 0.25) · in-band (<20 kHz) error %.1f dB\n",
           name, a0, a0x, a1, inband);
    fflush(stdout);
    assert(fabs(a0 - 0.5) < 0.005 && fabs(a1 - 0.25) < 0.005 && a0x < 0.001);
    assert(inband < -65);  // limited by this test's simple 2nd-order modulator, not the decoder
    // Exact check: direct double-precision convolution of the same filter on the raw bits.
    int N = 384; double *h = malloc(sizeof(double) * N), hs = 0, fc = 40000.0 / RATE, mid = (N - 1) / 2.0;
    for (int t = 0; t < N; t++) { double x = t - mid; h[t] = (x == 0 ? 2 * fc : sin(2 * M_PI * fc * x) / (M_PI * x)) * (0.42 - 0.5 * cos(2 * M_PI * t / (N - 1)) + 0.08 * cos(4 * M_PI * t / (N - 1))); hs += h[t]; }
    double maxdiff = 0;
    for (int j = 1000; j < 3000; j++) {
        long nb = 8L * (2L * j + 24) + 7; double y = 0;  // newest bit of the centred window
        for (int t = 0; t < N; t++) { long n = nb - t; int bit = (bits[0][n / 8] >> (7 - n % 8)) & 1; y += h[t] / hs * (bit ? 1 : -1); }
        double dd = fabs(y - pcm[2 * j]); if (dd > maxdiff) maxdiff = dd;
    }
    free(h);
    printf("%s PCM vs direct convolution: max diff %.2e\n", name, maxdiff);
    assert(maxdiff < 1e-5);
    // seek == continuous
    float *seg = malloc(sizeof(float) * 2 * 500);
    d->seek(d, 12345);
    assert(d->read(d, (uint8_t *) seg, 500) == 500);
    assert(!memcmp(seg, pcm + 2 * 12345, sizeof(float) * 2 * 500));
    d->close(d);
    // DoP path
    d = dsd_open(fd, 1, 0, err, sizeof err);
    assert(d && d->info.is_dop && d->info.format == DEC_S24 && d->info.sample_rate == 176400);
    uint8_t *dop = malloc(3 * 2 * d->info.total_frames);
    got = 0;
    while ((r = d->read(d, dop + 6 * got, 777)) > 0) got += r;
    assert(got == NBITS / 16);
    for (int fi = 0; fi < got; fi++)
        for (int c = 0; c < 2; c++) {
            const uint8_t *p = dop + 6 * fi + 3 * c;
            assert(p[2] == ((fi & 1) ? 0xFA : 0x05));
            assert(p[1] == bits[c][2 * fi] && p[0] == bits[c][2 * fi + 1]);
        }
    d->seek(d, 4321);
    uint8_t s3[6]; d->read(d, s3, 1);
    assert(!memcmp(s3, dop + 6 * 4321, 6));
    printf("%s DoP: %d frames bit-exact, markers alternate, seek OK · %s\n", name, got, d->info.desc);
    d->close(d);
    // DoP is still chosen when native DSD isn't allowed up to this rate
    d = dsd_open(fd, 1, RATE / 2, err, sizeof err);
    assert(d && d->info.is_dop && !d->info.is_native);
    d->close(d);
    // Native path: wins over DoP when allowed; 4 bytes per channel per frame, oldest byte first
    d = dsd_open(fd, 1, 24576000, err, sizeof err);
    assert(d && d->info.is_native && !d->info.is_dop && d->info.is_dsd);
    assert(d->info.format == DEC_S16 && d->info.channels == 4 && d->info.sample_rate == RATE / 32);
    assert(d->info.total_frames == NBITS / 32);
    uint8_t *nat = malloc(8 * d->info.total_frames);
    got = 0;
    while ((r = d->read(d, nat + 8 * got, 333)) > 0) got += r;
    assert(got == NBITS / 32);
    for (int fi = 0; fi < got; fi++)
        for (int c = 0; c < 2; c++)
            assert(!memcmp(nat + 8 * fi + 4 * c, &bits[c][4 * fi], 4));
    d->seek(d, 2222);
    uint8_t s8[8]; assert(d->read(d, s8, 1) == 1);
    assert(!memcmp(s8, nat + 8 * 2222, 8));
    printf("%s native: %d frames bit-exact, seek OK · %s\n", name, got, d->info.desc);
    d->close(d);
    free(nat);
    close(fd);
    free(pcm); free(seg); free(dop);
}

int main(void) {
    modulate();
    write_dsf("/tmp/allmeyn-native-tests/t.dsf");
    write_dff("/tmp/allmeyn-native-tests/t.dff");
    check_file("/tmp/allmeyn-native-tests/t.dsf", "DSF");
    check_file("/tmp/allmeyn-native-tests/t.dff", "DFF");
    check_dop_padding();
    check_pcm_join();
    // Higher rates: native frame rate = DSD rate / 32; DoP stops at DSD256; DSD512 without native -> PCM
    struct { uint32_t rate; int native_fr; int dop_rate; } hr[] = {
        {5644800, 176400, 352800}, {11289600, 352800, 705600}, {22579200, 705600, 0}, {24576000, 768000, 0},
    };
    for (int i = 0; i < 4; i++) {
        write_dsf_rate("/tmp/allmeyn-native-tests/hr.dsf", hr[i].rate);
        char e2[128] = "";
        int hfd = open("/tmp/allmeyn-native-tests/hr.dsf", O_RDONLY);
        Decoder *n = dsd_open(hfd, 1, 24576000, e2, sizeof e2);
        assert(n && n->info.is_native && n->info.sample_rate == hr[i].native_fr);
        uint8_t fr[8]; assert(n->read(n, fr, 1) == 1 && !memcmp(fr, bits[0], 4) && !memcmp(fr + 4, bits[1], 4));
        n->close(n);
        Decoder *p = dsd_open(hfd, 1, 0, e2, sizeof e2);
        assert(p);
        if (hr[i].dop_rate) assert(p->info.is_dop && p->info.sample_rate == hr[i].dop_rate);
        else assert(!p->info.is_dop && p->info.format == DEC_F32);
        unsigned cls = 64u * (hr[i].rate % 44100 ? hr[i].rate / 3072000u : hr[i].rate / 2822400u);
        printf("DSD%u: native %d Hz frames · %s\n", cls, hr[i].native_fr, p->info.desc);
        p->close(p);
        close(hfd);
    }
    char err[128] = "";
    int fd = open(__FILE__, O_RDONLY);
    assert(dsd_open(fd, 0, 0, err, sizeof err) == NULL);  // not DSD
    puts("all DSD tests passed");
    return 0;
}
