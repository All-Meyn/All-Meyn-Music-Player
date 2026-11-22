// Float frames -> the device's integer format, for exclusive (bit-perfect) output. Exact for
// integer sources: a 16-bit sample s arrives as s/32768 and leaves as s again; a 24-bit one (incl.
// DoP words, marker byte and all) as s/2^23 and back. Wider containers just pad with zero bits.
#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace allmeyn {

enum class SampleFormat {
    Float32,      // 32-bit float
    Int16,        // 16-bit
    Int24In32,    // 24 valid bits, left-aligned in 32 (low byte zero)
    Int32,        // 32-bit
    Int24Packed,  // 3 bytes
};

inline int bytesPerSample(SampleFormat f) {
    switch (f) {
        case SampleFormat::Int16: return 2;
        case SampleFormat::Int24Packed: return 3;
        default: return 4;
    }
}

/** Valid bits of [f] (what the DAC sees). */
inline int validBits(SampleFormat f) {
    switch (f) {
        case SampleFormat::Int16: return 16;
        case SampleFormat::Int24In32:
        case SampleFormat::Int24Packed: return 24;
        default: return 32;
    }
}

namespace detail {
inline int64_t scaled(float v, double scale, int64_t lo, int64_t hi) {
    const int64_t x = (int64_t) std::llrint((double) v * scale);
    return x < lo ? lo : (x > hi ? hi : x);
}
}  // namespace detail

/** [samples] floats (interleaved) -> [out] in [f], little-endian. */
inline void convertFromFloat(const float* in, uint8_t* out, size_t samples, SampleFormat f) {
    switch (f) {
        case SampleFormat::Float32:
            memcpy(out, in, samples * sizeof(float));
            break;
        case SampleFormat::Int16:
            for (size_t i = 0; i < samples; i++) {
                const int16_t v = (int16_t) detail::scaled(in[i], 32768.0, -32768, 32767);
                memcpy(out + 2 * i, &v, 2);
            }
            break;
        case SampleFormat::Int24In32:
            for (size_t i = 0; i < samples; i++) {
                const int32_t v = (int32_t) ((uint32_t) detail::scaled(in[i], 8388608.0, -8388608, 8388607) << 8);
                memcpy(out + 4 * i, &v, 4);
            }
            break;
        case SampleFormat::Int32:
            for (size_t i = 0; i < samples; i++) {
                const int32_t v = (int32_t) detail::scaled(in[i], 2147483648.0, INT32_MIN, INT32_MAX);
                memcpy(out + 4 * i, &v, 4);
            }
            break;
        case SampleFormat::Int24Packed:
            for (size_t i = 0; i < samples; i++) {
                const int32_t v = (int32_t) detail::scaled(in[i], 8388608.0, -8388608, 8388607);
                out[3 * i] = (uint8_t) v;
                out[3 * i + 1] = (uint8_t) (v >> 8);
                out[3 * i + 2] = (uint8_t) (v >> 16);
            }
            break;
    }
}

/** A packed little-endian 24-bit sample as float (exact). */
inline float int24ToFloat(const uint8_t* p) {
    int32_t v = p[0] | (p[1] << 8) | (p[2] << 16);
    if (v & 0x800000) v -= 0x1000000;
    return (float) v / 8388608.0f;
}

}  // namespace allmeyn
