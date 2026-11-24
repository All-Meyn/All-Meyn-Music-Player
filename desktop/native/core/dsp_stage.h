// The Android app's DSP chain (app/src/main/cpp/dsp, used unchanged) in the desktop player: runs
// on the output's thread, on the frames just pulled, so settings apply at once. Off (or nothing to
// do) = the frames aren't touched. Only PCM with 1-2 channels is processed, never DoP / native DSD
// (with the DSP on, the player opens DSD as PCM).
//
// Threads: settings from the control thread, song changes from the decoder, process() from the
// output. The output thread never waits on the others: it picks up changes with try_lock (a busy
// lock just means the change lands one buffer later).
#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

#include "dsp_chain.h"
#include "source.h"

namespace allmeyn {

struct DspSettings {
    bool enabled = false;  // the master switch
    dsp::Params params;
    /** Whether the chain changes the audio at all (master on and something to do). */
    bool processes() const {
        return enabled && (params.companderOn || params.bassOn || params.impactOn || params.vocalOn ||
                           params.volumeDb != 0.0f || params.softClip ||
                           (params.eqOn && (params.eqBandCount > 0 || params.eqPreampDb != 0.0f)));
    }
};

/** What the file says for Air: lossy or not, and a first guess at a lossy encoder's treble cutoff. */
struct AirHint {
    bool lossy = false;
    float priorCutoffHz = 0;  // 0 = none / full range
};

/**
 * The Android AirSource table: typical encoder lowpass by bitrate (stereo; mono counts double).
 * Opus is full-band; unknown bitrate guesses 16 kHz; 256 kbps and up count as full range.
 */
AirHint airHintFor(const SourceInfo& info);

class DspStage {
public:
    /** Any thread. */
    void setSettings(const DspSettings& s);
    /** The settings say the chain changes the audio (whether or not this stream can be processed). */
    bool wantsProcessing() const { return processes_.load(); }
    /** The current stream is being processed. */
    bool running() const { return processes_.load() && hasChain_.load(); }

    /** A new stream (the output isn't pulling yet): [pcm] false for DoP / native DSD. */
    void configure(int sampleRate, int channels, bool pcm, AirHint first);
    void release();
    /** Decoder thread: the song starting at stream frame [at] (gapless join). */
    void sourceAt(int64_t at, AirHint hint);
    /** After a seek: fresh filters, and [hint] from the new position on. */
    void restart(AirHint hint);

    /** Output thread: [frames] interleaved frames in place; [firstFrame] = their stream frame. */
    void process(float* data, int frames, int64_t firstFrame);

    /** The latest meters; false when the chain isn't running. */
    bool meters(dsp::Meters& out) const;

private:
    struct PendingSource { int64_t at; AirHint hint; };
    static constexpr int kMaxPendingSources = 8;

    std::mutex chainLock_;  // held by process(); configure/release only when the output isn't pulling
    std::unique_ptr<dsp::Chain> chain_;
    std::atomic<bool> hasChain_{false};

    std::mutex pendingLock_;
    DspSettings pending_;
    uint64_t version_ = 0;
    std::array<PendingSource, kMaxPendingSources> sources_{};
    int sourceCount_ = 0;
    bool resetPending_ = false;
    std::atomic<bool> processes_{false};

    // Output thread only.
    DspSettings settings_;
    uint64_t applied_ = ~0ull;

    mutable std::mutex metersLock_;
    dsp::Meters meters_{};
    bool metersValid_ = false;
};

}  // namespace allmeyn
