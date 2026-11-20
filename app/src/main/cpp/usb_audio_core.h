// Pure (USB-independent) parts of the All-Meyn USB audio engine: the lock-free ring buffer and
// PCM conversion into the DAC's slot format. Kept separate so it can be unit-tested on a host.

#ifndef ALLMEYN_USB_AUDIO_CORE_H
#define ALLMEYN_USB_AUDIO_CORE_H

#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>

// Input sample formats (mapped from Media3 encodings on the Kotlin side).
enum {
    IN_S16 = 1,
    IN_S24 = 2,  // packed, 3 bytes
    IN_S32 = 3,
    IN_FLOAT = 4,
    // Raw DSD for the DAC's native DSD mode, carried by Media3 as 16-bit PCM with two "samples"
    // per DSD channel (see decoder/dsd.c). The bytes are copied into the 32-bit slots unchanged.
    IN_DSD_NATIVE = 5,
};

/** DSD idle pattern: what a DAC in native DSD mode gets when there is nothing to play. */
#define DSD_IDLE_BYTE 0x69

/**
 * Single-producer (playback thread) / single-consumer (USB event thread) ring of frames already
 * converted to the DAC's format. Positions are monotonically increasing frame counters.
 */
typedef struct {
    uint8_t *data;
    uint32_t capacity_frames;
    uint32_t frame_bytes;
    _Atomic uint64_t write_pos;
    _Atomic uint64_t read_pos;
} Ring;

int ring_init(Ring *r, uint32_t capacity_frames, uint32_t frame_bytes);
void ring_free(Ring *r);
uint32_t ring_available(Ring *r);
uint32_t ring_space(Ring *r);

/** Producer only: discards everything not yet read. */
void ring_flush(Ring *r);

/**
 * Producer only: converts interleaved PCM [in, in + in_bytes) and appends whole frames that fit.
 * Returns the number of INPUT bytes consumed (a multiple of the input frame size).
 */
size_t ring_write_converted(Ring *r, const uint8_t *in, size_t in_bytes, int in_format,
                            int in_channels, int out_subslot, int out_channels);

/**
 * Consumer only: copies up to [frames] frames into [dst]. Returns frames copied. Returns 0 if a
 * flush raced with the read (the caller then plays silence for this packet).
 */
uint32_t ring_read(Ring *r, uint8_t *dst, uint32_t frames);

/** Converts one input sample to a left-justified 32-bit value. Exact for 16/24-bit and float. */
int32_t sample_to_s32(const uint8_t *p, int in_format);

/** Writes a left-justified 32-bit sample into an output slot of 2, 3 or 4 bytes. */
void s32_to_slot(uint8_t *p, int subslot, int32_t v);

int input_sample_bytes(int in_format);

#endif
