#include "usb_audio_core.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

int input_sample_bytes(int in_format) {
    switch (in_format) {
        case IN_S16: return 2;
        case IN_S24: return 3;
        case IN_S32:
        case IN_FLOAT: return 4;
        default: return 0;
    }
}

int ring_init(Ring *r, uint32_t capacity_frames, uint32_t frame_bytes) {
    r->data = calloc(capacity_frames, frame_bytes);
    if (!r->data) return -1;
    r->capacity_frames = capacity_frames;
    r->frame_bytes = frame_bytes;
    atomic_store(&r->write_pos, 0);
    atomic_store(&r->read_pos, 0);
    return 0;
}

void ring_free(Ring *r) {
    free(r->data);
    r->data = NULL;
    r->capacity_frames = 0;
}

uint32_t ring_available(Ring *r) {
    uint64_t w = atomic_load(&r->write_pos);
    uint64_t rd = atomic_load(&r->read_pos);
    return w > rd ? (uint32_t) (w - rd) : 0;
}

uint32_t ring_space(Ring *r) {
    return r->capacity_frames - ring_available(r);
}

void ring_flush(Ring *r) {
    atomic_store(&r->read_pos, atomic_load(&r->write_pos));
}

int32_t sample_to_s32(const uint8_t *p, int in_format) {
    switch (in_format) {
        case IN_S16: {
            int16_t v;
            memcpy(&v, p, 2);
            return (int32_t) ((uint32_t) (int32_t) v << 16);
        }
        case IN_S24:
            return (int32_t) (((uint32_t) p[0] << 8) | ((uint32_t) p[1] << 16) | ((uint32_t) p[2] << 24));
        case IN_S32: {
            int32_t v;
            memcpy(&v, p, 4);
            return v;
        }
        case IN_FLOAT: {
            float f;
            memcpy(&f, p, 4);
            double d = (double) f * 2147483648.0;  // exact for 16/24-bit sources
            if (d >= 2147483647.0) return INT32_MAX;
            if (d <= -2147483648.0) return INT32_MIN;
            if (d != d) return 0;  // NaN
            return (int32_t) lrint(d);
        }
        default:
            return 0;
    }
}

void s32_to_slot(uint8_t *p, int subslot, int32_t v) {
    uint32_t u = (uint32_t) v;
    switch (subslot) {
        case 2: p[0] = (uint8_t) (u >> 16); p[1] = (uint8_t) (u >> 24); break;
        case 3: p[0] = (uint8_t) (u >> 8); p[1] = (uint8_t) (u >> 16); p[2] = (uint8_t) (u >> 24); break;
        default: p[0] = (uint8_t) u; p[1] = (uint8_t) (u >> 8); p[2] = (uint8_t) (u >> 16); p[3] = (uint8_t) (u >> 24); break;
    }
}

size_t ring_write_converted(Ring *r, const uint8_t *in, size_t in_bytes, int in_format,
                            int in_channels, int out_subslot, int out_channels) {
    int sb = input_sample_bytes(in_format);
    if (sb == 0 || in_channels <= 0 || r->data == NULL) return 0;
    size_t in_frame = (size_t) sb * (size_t) in_channels;
    uint32_t frames = (uint32_t) (in_bytes / in_frame);
    uint32_t space = ring_space(r);
    if (frames > space) frames = space;

    uint64_t w = atomic_load(&r->write_pos);
    int fast_copy = (in_format == IN_S16 && out_subslot == 2 && in_channels == out_channels);
    for (uint32_t f = 0; f < frames; f++) {
        uint8_t *dst = r->data + (size_t) ((w + f) % r->capacity_frames) * r->frame_bytes;
        const uint8_t *src = in + (size_t) f * in_frame;
        if (fast_copy) {
            memcpy(dst, src, in_frame);  // 16-bit to a 16-bit DAC slot: bit-perfect copy
            continue;
        }
        for (int c = 0; c < out_channels; c++) {
            int ic = c < in_channels ? c : in_channels - 1;  // mono -> both channels
            int32_t v = sample_to_s32(src + (size_t) ic * sb, in_format);
            s32_to_slot(dst + (size_t) c * out_subslot, out_subslot, v);
        }
    }
    atomic_store(&r->write_pos, w + frames);
    return (size_t) frames * in_frame;
}

uint32_t ring_read(Ring *r, uint8_t *dst, uint32_t frames) {
    uint64_t rd = atomic_load(&r->read_pos);
    uint64_t w = atomic_load(&r->write_pos);
    uint32_t avail = w > rd ? (uint32_t) (w - rd) : 0;
    if (frames > avail) frames = avail;
    for (uint32_t f = 0; f < frames; f++) {
        memcpy(dst + (size_t) f * r->frame_bytes,
               r->data + (size_t) ((rd + f) % r->capacity_frames) * r->frame_bytes,
               r->frame_bytes);
    }
    // If a flush moved read_pos meanwhile, drop what we copied.
    uint64_t expected = rd;
    if (!atomic_compare_exchange_strong(&r->read_pos, &expected, rd + frames)) return 0;
    return frames;
}
