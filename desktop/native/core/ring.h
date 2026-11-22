// Single-producer single-consumer ring of interleaved float frames (decode thread -> output).
#pragma once

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

namespace allmeyn {

class FrameRing {
public:
    void init(int capacityFrames, int channels) {
        channels_ = channels;
        capacity_ = capacityFrames;
        data_.assign((size_t) capacityFrames * channels, 0.0f);
        read_.store(0);
        write_.store(0);
    }
    int channels() const { return channels_; }
    int64_t available() const { return write_.load(std::memory_order_acquire) - read_.load(std::memory_order_acquire); }
    int64_t space() const { return capacity_ - available(); }

    /** Producer. Returns frames written (may be fewer than n when full). */
    int write(const float* src, int n) {
        const int64_t w = write_.load(std::memory_order_relaxed);
        n = (int) std::min<int64_t>(n, capacity_ - (w - read_.load(std::memory_order_acquire)));
        copy(src, w, n, /*toRing=*/true);
        write_.store(w + n, std::memory_order_release);
        return n;
    }

    /** Consumer. Returns frames read. */
    int read(float* dst, int n) {
        const int64_t r = read_.load(std::memory_order_relaxed);
        n = (int) std::min<int64_t>(n, write_.load(std::memory_order_acquire) - r);
        copy(dst, r, n, /*toRing=*/false);
        read_.store(r + n, std::memory_order_release);
        return n;
    }

    /** Drops everything queued. Only while the consumer isn't reading (the player locks around it). */
    void clear() { read_.store(write_.load(std::memory_order_acquire), std::memory_order_release); }

private:
    void copy(float* outside, int64_t pos, int n, bool toRing) {
        int done = 0;
        while (done < n) {
            const int at = (int) ((pos + done) % capacity_);
            const int run = std::min(n - done, capacity_ - at);
            float* ring = data_.data() + (size_t) at * channels_;
            float* other = outside + (size_t) done * channels_;
            if (toRing) memcpy(ring, other, (size_t) run * channels_ * sizeof(float));
            else memcpy(other, ring, (size_t) run * channels_ * sizeof(float));
            done += run;
        }
    }
    void copy(const float* src, int64_t pos, int n, bool) { copy(const_cast<float*>(src), pos, n, true); }

    std::vector<float> data_;
    int capacity_ = 0, channels_ = 0;
    std::atomic<int64_t> read_{0}, write_{0};
};

}  // namespace allmeyn
