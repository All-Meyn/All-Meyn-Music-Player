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

/**
 * Decides which short packets are real dropouts. A packet the ring couldn't fill is only an
 * underrun if audio was flowing (a full packet since the last start / seek / pause), and each gap
 * counts once until audio flows again. Stream starts, seeks, pauses and the drain at the end of
 * the queue (after underrun_end_of_stream) don't count.
 */
typedef struct {
    atomic_int armed;     // audio has been flowing since the last (re)start (USB event thread)
    atomic_int draining;  // end of stream announced: running dry is expected
} UnderrunState;

/** Call for every packet while playing music; returns 1 if this packet is a new dropout. */
int underrun_note(UnderrunState *s, int packet_full);
/** Start, seek, flush, pause, or new data after the end of stream: wait for audio to flow again. */
void underrun_restart(UnderrunState *s);
/** The player is playing out the last of the queue. */
void underrun_end_of_stream(UnderrunState *s);

/** DSD idle pattern: what a DAC in native DSD mode gets when there is nothing to play. */
#define DSD_IDLE_BYTE 0x69

/*
 * Native DSD settle: after a flush (seek, skip) the DAC gets this much DSD silence before the new
 * audio. DSD DACs mute softly after a moment of DSD silence; a seek leaves only milliseconds between
 * the old audio and the new, so without this the splice of two points in a 1-bit stream is heard as
 * a burst of noise. A pause already gives that silence, which is why pause/resume was clean.
 */
#define DSD_SETTLE_MS 150

/** Takes up to [frames] from the settle budget [hold]; returns how many it took (0 = none left). */
uint32_t settle_take(atomic_uint *hold, uint32_t frames);

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
