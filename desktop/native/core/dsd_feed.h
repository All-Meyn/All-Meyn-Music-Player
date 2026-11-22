// Native DSD frames (one 32-bit word per channel per frame: 4 bytes, oldest first, MSB = oldest bit,
// carried raw in the player's float slots) -> one byte stream per channel, of any length per call
// (ASIO buffers needn't be a multiple of 4 bytes). Leftover bytes of a word wait for the next call.
#pragma once

#include <cstdint>
#include <cstring>
#include <vector>

#include "output.h"

namespace allmeyn {

constexpr uint8_t kDsdSilence = 0x69;  // the DSD idle pattern (MSB-first)

inline uint8_t reverseBits(uint8_t b) {
    b = (uint8_t) ((b & 0xF0) >> 4 | (b & 0x0F) << 4);
    b = (uint8_t) ((b & 0xCC) >> 2 | (b & 0x33) << 2);
    return (uint8_t) ((b & 0xAA) >> 1 | (b & 0x55) << 1);
}

class DsdFeeder {
public:
    /** [maxBytes]: the most one fill() asks for, so the audio thread never allocates. */
    void init(int channels, int maxBytes = 0) {
        channels_ = channels;
        frames_.resize((size_t) ((maxBytes + 3) / 4) * channels);
        carry_.assign((size_t) channels * 4, 0);
        carryLen_ = carryPos_ = 0;
    }

    /**
     * Fills outs[c][0, bytes) for each channel from [pull]; past the end of what [pull] gives, DSD
     * silence. [lsbFirst]: first sample in the least significant bit (ASIOSTDSDInt8LSB1).
     * Returns how many of the bytes are real audio.
     */
    int fill(const PullFn& pull, uint8_t* const* outs, int bytes, bool lsbFirst) {
        int done = 0;
        // Bytes left over from the last word first.
        while (done < bytes && carryPos_ < carryLen_) {
            for (int c = 0; c < channels_; c++) outs[c][done] = carry_[(size_t) c * 4 + carryPos_];
            carryPos_++;
            done++;
        }
        int real = done;
        if (done < bytes) {
            const int frames = (bytes - done + 3) / 4;
            if (frames_.size() < (size_t) frames * channels_) frames_.resize((size_t) frames * channels_);
            const int got = pull(frames_.data(), frames);
            const uint8_t* raw = reinterpret_cast<const uint8_t*>(frames_.data());
            carryLen_ = carryPos_ = 0;
            for (int f = 0; f < got; f++) {
                for (int k = 0; k < 4; k++) {
                    for (int c = 0; c < channels_; c++) {
                        const uint8_t b = raw[((size_t) f * channels_ + c) * 4 + k];
                        if (done < bytes) outs[c][done] = b;
                        else carry_[(size_t) c * 4 + carryLen_] = b;  // only in the last word
                    }
                    if (done < bytes) { done++; real++; } else carryLen_++;
                }
            }
            for (; done < bytes; done++) for (int c = 0; c < channels_; c++) outs[c][done] = kDsdSilence;
        }
        if (lsbFirst) for (int c = 0; c < channels_; c++) for (int i = 0; i < bytes; i++) outs[c][i] = reverseBits(outs[c][i]);
        return real;
    }

    /** Forget leftovers (after a pause or seek, nothing old should come out). */
    void reset() { carryLen_ = carryPos_ = 0; }

private:
    int channels_ = 0;
    std::vector<uint8_t> carry_;  // per channel, up to 3 bytes of a word not yet sent
    int carryLen_ = 0, carryPos_ = 0;
    std::vector<float> frames_;
};

}  // namespace allmeyn
