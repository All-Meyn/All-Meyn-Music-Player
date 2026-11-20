// All-Meyn file decoders: formats Media3 can't read itself (DSF, DFF, APE, WavPack).
// Each decoder turns a file (read with pread on a file descriptor, so seeking is free) into
// interleaved little-endian PCM frames. The Kotlin side wraps that in an RF64 WAV stream that
// Media3's WavExtractor plays and seeks.

#ifndef ALLMEYN_DECODER_H
#define ALLMEYN_DECODER_H

#include <stdint.h>

// Output sample formats.
enum {
    DEC_S16 = 1,
    DEC_S24 = 2,  // packed 3 bytes
    DEC_S32 = 3,
    DEC_F32 = 4,
};

typedef struct {
    int sample_rate;
    int channels;
    int format;
    int64_t total_frames;
    int is_dsd;  // source is DSD (DSF/DFF)
    int is_dop;  // output is DoP: DSD packed in 24-bit PCM, only for a DoP-capable DAC
    char desc[96];
} DecInfo;

typedef struct Decoder Decoder;
struct Decoder {
    DecInfo info;
    /** Writes up to max_frames interleaved frames to out. Returns frames written, 0 at end, <0 on error. */
    int (*read)(Decoder *d, uint8_t *out, int max_frames);
    /** Positions so the next read starts at output frame [frame]. Returns 0 or <0 on error. */
    int (*seek)(Decoder *d, int64_t frame);
    void (*close)(Decoder *d);
};

int dec_sample_bytes(int format);

/** DSF/DFF. [dop]: pack DSD as DoP (when the rate allows); otherwise convert to PCM. NULL if not DSD. */
Decoder *dsd_open(int fd, int dop, char *err, int err_len);

/** APE/WavPack through FFmpeg. NULL if not supported or FFmpeg isn't built in. */
Decoder *ff_open(int fd, char *err, int err_len);

#endif
