// Writes tagged DSF test tones for checking native DSD on a DAC: 440 Hz left, 660 Hz right at
// -14 dBFS, through a 2nd-order sigma-delta modulator. One file per rate: DSD64 .. DSD512.
//
//   gcc -O2 -o /tmp/dsdtones scripts/make-dsd-test-tones.c -lm && /tmp/dsdtones OUT_DIR [seconds]
//
// Files show up in the library as album "DSD test tones" by "All-Meyn".
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void le32(FILE *f, uint32_t v) { for (int i = 0; i < 4; i++) fputc((v >> (8 * i)) & 0xFF, f); }
static void le64(FILE *f, uint64_t v) { for (int i = 0; i < 8; i++) fputc((v >> (8 * i)) & 0xFF, f); }

/** ID3v2.3 tag with text frames; returns its size. */
static size_t id3(uint8_t *out, const char *title, const char *artist, const char *album, const char *track) {
    const char *ids[] = {"TIT2", "TPE1", "TALB", "TRCK"};
    const char *vals[] = {title, artist, album, track};
    size_t n = 10;
    for (int i = 0; i < 4; i++) {
        size_t len = strlen(vals[i]) + 1;  // encoding byte + text
        memcpy(out + n, ids[i], 4);
        out[n + 4] = (uint8_t) (len >> 24); out[n + 5] = (uint8_t) (len >> 16);
        out[n + 6] = (uint8_t) (len >> 8); out[n + 7] = (uint8_t) len;
        out[n + 8] = out[n + 9] = 0;
        out[n + 10] = 0;  // ISO-8859-1
        memcpy(out + n + 11, vals[i], len - 1);
        n += 10 + len;
    }
    size_t body = n - 10;
    memcpy(out, "ID3\x03\x00\x00", 6);
    for (int i = 0; i < 4; i++) out[6 + i] = (uint8_t) ((body >> (7 * (3 - i))) & 0x7F);  // syncsafe
    return n;
}

static void make(const char *dir, uint32_t rate, int dsd_class, int track, double seconds) {
    const uint32_t B = 4096;
    uint64_t nbits = (uint64_t) (rate * seconds) / 8 * 8;
    uint64_t bpc = nbits / 8, blocks = (bpc + B - 1) / B, data = blocks * B * 2;
    uint8_t *bits[2];
    double fr[2] = {440, 660}, amp = 0.2;  // -14 dBFS
    for (int c = 0; c < 2; c++) {
        bits[c] = calloc(blocks * B, 1);
        double i1 = 0, i2 = 0, y = 0;
        for (uint64_t n = 0; n < nbits; n++) {
            double fade = fmin(1.0, fmin(n, nbits - n) / (0.05 * rate));  // 50 ms fade in/out
            double x = amp * fade * sin(2 * M_PI * fr[c] * (double) n / rate);
            i1 += x - y; i2 += i1 - y;
            y = i2 >= 0 ? 1 : -1;
            if (y > 0) bits[c][n / 8] |= (uint8_t) (1u << (n % 8));  // DSF: LSB = oldest bit
        }
        for (uint64_t i = bpc; i < blocks * B; i++) bits[c][i] = 0x69;  // pad
    }
    char title[64], path[512], trk[8];
    snprintf(title, sizeof title, "DSD%d test tone", dsd_class);
    snprintf(trk, sizeof trk, "%d", track);
    uint8_t tag[512];
    size_t tag_len = id3(tag, title, "All-Meyn", "DSD test tones", trk);
    snprintf(path, sizeof path, "%s/%02d DSD%d test tone.dsf", dir, track, dsd_class);
    FILE *f = fopen(path, "wb");
    if (!f) { perror(path); exit(1); }
    uint64_t meta = 28 + 52 + 12 + data;
    fwrite("DSD ", 1, 4, f); le64(f, 28); le64(f, meta + tag_len); le64(f, meta);
    fwrite("fmt ", 1, 4, f); le64(f, 52); le32(f, 1); le32(f, 0); le32(f, 2); le32(f, 2);
    le32(f, rate); le32(f, 1); le64(f, nbits); le32(f, B); le32(f, 0);
    fwrite("data", 1, 4, f); le64(f, 12 + data);
    for (uint64_t b = 0; b < blocks; b++)
        for (int c = 0; c < 2; c++) fwrite(bits[c] + b * B, 1, B, f);
    fwrite(tag, 1, tag_len, f);
    fclose(f);
    free(bits[0]); free(bits[1]);
    printf("%s (%.1f MB)\n", path, (meta + tag_len) / 1e6);
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s OUT_DIR [seconds]\n", argv[0]); return 1; }
    double s = argc > 2 ? atof(argv[2]) : 6;
    make(argv[1], 2822400, 64, 1, s);
    make(argv[1], 5644800, 128, 2, s);
    make(argv[1], 11289600, 256, 3, s);
    make(argv[1], 22579200, 512, 4, s);
    return 0;
}
