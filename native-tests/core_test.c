#include "usb_audio_core.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
int main(void) {
    // 1. s16 -> float -> s32 -> 16-bit slot is bit-exact for every 16-bit value
    for (int v = -32768; v <= 32767; v++) {
        float f = v / 32768.0f; uint8_t in[4]; memcpy(in, &f, 4);
        int32_t s = sample_to_s32(in, IN_FLOAT); uint8_t o[2]; s32_to_slot(o, 2, s);
        int16_t back; memcpy(&back, o, 2); assert(back == v);
        assert(s == (int32_t)((uint32_t)v << 16));
    }
    // 2. 24-bit -> float -> s32 exact (as FFmpeg would produce: s32 / 2^31)
    for (int v = -8388608; v <= 8388607; v += 7) {
        float f = (float)((double)((int32_t)((uint32_t)v << 8)) / 2147483648.0);
        uint8_t in[4]; memcpy(in, &f, 4);
        int32_t s = sample_to_s32(in, IN_FLOAT); assert(s == (int32_t)((uint32_t)v << 8));
        uint8_t p[3] = {(uint8_t)v, (uint8_t)(v>>8), (uint8_t)(v>>16)};
        assert(sample_to_s32(p, IN_S24) == s);
    }
    // 3. clipping
    float big = 1.5f; uint8_t in[4]; memcpy(in, &big, 4); assert(sample_to_s32(in, IN_FLOAT) == INT32_MAX);
    big = -1.0f; memcpy(in, &big, 4); assert(sample_to_s32(in, IN_FLOAT) == INT32_MIN);
    // 4. ring: write/read wrap, mono->stereo, flush race behaviour
    Ring r; assert(ring_init(&r, 10, 4) == 0);  // 10 frames of 16-bit stereo
    int16_t mono[7]; for (int i = 0; i < 7; i++) mono[i] = (int16_t)(i * 100);
    size_t used = ring_write_converted(&r, (uint8_t*)mono, sizeof mono, IN_S16, 1, 2, 2);
    assert(used == 14 && ring_available(&r) == 7);
    int16_t out[20]; assert(ring_read(&r, (uint8_t*)out, 5) == 5);
    for (int i = 0; i < 5; i++) assert(out[2*i] == i*100 && out[2*i+1] == i*100);
    int16_t st[20]; for (int i = 0; i < 20; i++) st[i] = (int16_t)(1000 + i);
    used = ring_write_converted(&r, (uint8_t*)st, sizeof st, IN_S16, 2, 2, 2);  // 10 frames offered, space 8
    assert(used == 8 * 4 && ring_available(&r) == 10 && ring_space(&r) == 0);
    assert(ring_read(&r, (uint8_t*)out, 2) == 2 && out[0] == 500 && out[2] == 600);
    assert(ring_read(&r, (uint8_t*)out, 8) == 8);  // wraps
    for (int i = 0; i < 16; i++) assert(out[i] == 1000 + i);
    ring_write_converted(&r, (uint8_t*)st, 12, IN_S16, 2, 2, 2); ring_flush(&r);
    assert(ring_available(&r) == 0 && ring_read(&r, (uint8_t*)out, 4) == 0);
    // partial input frame is not consumed
    assert(ring_write_converted(&r, (uint8_t*)st, 6, IN_S16, 2, 2, 2) == 4);
    ring_free(&r);
    // 5. s16 -> 32-bit slot
    int16_t v = -12345; int32_t s = sample_to_s32((uint8_t*)&v, IN_S16); uint8_t o4[4]; s32_to_slot(o4, 4, s);
    int32_t back4; memcpy(&back4, o4, 4); assert(back4 == (int32_t)((uint32_t)(int32_t)v << 16));
    // 6. native DSD: bytes copied into 32-bit stereo slots unchanged; 4 x 16-bit in = 1 frame
    Ring d; assert(ring_init(&d, 6, 8) == 0);
    uint8_t dsd[8 * 5]; for (int i = 0; i < (int) sizeof dsd; i++) dsd[i] = (uint8_t) (i * 37 + 11);
    assert(ring_write_converted(&d, dsd, sizeof dsd + 3, IN_DSD_NATIVE, 4, 4, 2) == sizeof dsd);
    uint8_t dout[8 * 5]; assert(ring_read(&d, dout, 5) == 5 && !memcmp(dout, dsd, sizeof dsd));
    // wrong slot layout is refused rather than garbled
    assert(ring_write_converted(&d, dsd, 8, IN_DSD_NATIVE, 4, 3, 2) == 0);
    ring_free(&d);
    puts("core tests passed");
    return 0;
}
