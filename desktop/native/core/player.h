// Plays songs: a decode thread fills a ring from the Source, the Output pulls from it.
// Gapless: a next song with the same rate and channels ([setNext]) is decoded straight after the
// current one into the same stream; the song "heard" switches when the output reaches the join.
// Thread-safe for one controlling thread (the UI's JNI calls) plus its own two threads.
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include "dsp_stage.h"
#include "output.h"
#include "replay_gain.h"
#include "ring.h"
#include "source.h"

namespace allmeyn {

enum class PlayerState : int { Idle = 0, Playing = 1, Paused = 2, Ended = 3, Error = 4 };

class Player {
public:
    explicit Player(OutputFactory outputs);
    ~Player();

    /** Opens [utf8Path] paused at the start (forgets any next song). False with [error] set. */
    bool open(const std::string& utf8Path, std::string& error);
    /**
     * The song to play after the current one. Played gaplessly if it has the same rate and channels;
     * otherwise the player ends as usual and the caller opens it. False with [error] if unplayable.
     */
    bool setNext(const std::string& utf8Path, std::string& error);
    void clearNext();
    /**
     * How DSD may reach the output, best first: native (an output that does native DSD), DoP (a
     * bit-exact output), else PCM. Applies to songs opened next.
     */
    void setDsdModes(bool dop, bool native) { dopAllowed_ = dop; nativeAllowed_ = native; }
    void setDopAllowed(bool allowed) { setDsdModes(allowed, false); }
    /** ReplayGain for songs opened from now on (the app passes Off for bit-perfect outputs). */
    void setReplayGain(ReplayGainMode m) { rgMode_ = (int) m; }
    /** The DSP chain's settings (any time; applies at once). With processing on, DSD opens as PCM. */
    void setDsp(const DspSettings& s) { dsp_.setSettings(s); }
    /** The convolution EQ's filter (null = none); built for the stream right away. */
    void setConvolution(std::shared_ptr<const dsp::ConvSource> s) { dsp_.setConvolution(std::move(s)); }
    /** The chain's meters while it processes the stream. */
    bool dspMeters(dsp::Meters& out) const { return dsp_.meters(out); }
    void play();
    void pause();
    /** Jumps to [frame] of the song being heard (sample-accurate); keeps playing or paused. */
    void seek(int64_t frame);
    void close();

    PlayerState state() const;
    /** Frame of the song being heard. */
    int64_t positionFrames() const;
    /** The song being heard. */
    SourceInfo info() const;
    /** Counts gapless moves to the next song (the caller then queues the one after). */
    int trackSerial() const;
    std::string error() const;
    std::string outputDescription() const;

    /** The output's pull (public for tests that drive a fake output). */
    int pull(float* out, int frames);

private:
    struct Boundary {
        int64_t at;        // frames written (since open/seek) where the next song starts
        SourceInfo info;
    };
    void decodeLoop();
    void stopThreads();
    /** Moves "heard" past joins the output has played through. Control thread. */
    void advanceHeard() const;
    bool nextIsCompatible() const;

    OutputFactory outputs_;
    std::unique_ptr<Output> output_;
    FrameRing ring_;

    // Decode thread's: the source being decoded (may already be the next song, after a join).
    std::unique_ptr<Source> source_;
    std::thread decoder_;
    std::mutex wakeLock_;
    std::condition_variable wake_;
    std::atomic<bool> quit_{false};
    // A seek is pending while seekGen_ != handledGen_; counting (not a flag) means a second seek
    // arriving while the decoder handles the first is never lost.
    std::atomic<uint64_t> seekGen_{0}, handledGen_{0};
    std::atomic<int64_t> seekTarget_{0};
    bool seekPending() const { return seekGen_.load() != handledGen_.load(); }
    std::atomic<bool> sourceEnded_{false};
    std::atomic<int64_t> written_{0};      // frames written to the ring since open/seek
    std::atomic<int> streamRate_{0}, streamChannels_{0};  // the stream's format (fixed per open)
    std::atomic<bool> streamDop_{false}, streamNative_{false};
    std::atomic<bool> dopAllowed_{false}, nativeAllowed_{false};
    DsdMode bestDsdMode() const {
        if (dsp_.wantsProcessing()) return DsdMode::Pcm;  // the DSP works on PCM
        return nativeAllowed_ ? DsdMode::Native : dopAllowed_ ? DsdMode::Dop : DsdMode::Pcm;
    }
    DspStage dsp_;
    std::atomic<int> rgMode_{0};
    /** Sets [s]'s ReplayGain from its tags and the mode (PCM only). */
    void prepareGain(Source& s) const;

    mutable std::mutex nextLock_;
    std::unique_ptr<Source> next_;
    std::atomic<bool> nextReady_{false};    // next_ is set and compatible

    // What's heard. Boundaries are pushed by the decoder, popped as the output passes them.
    mutable std::mutex trackLock_;
    mutable std::deque<Boundary> boundaries_;
    mutable SourceInfo heard_;
    mutable int64_t trackStart_ = 0;        // consumed_ value where the heard song starts
    mutable std::atomic<int> trackSerial_{0};
    bool reopenHeard_ = false;              // a seek came while a join was pending (trackLock_)

    mutable std::mutex readLock_;      // pull() vs clearing the ring on a seek
    mutable std::atomic<int64_t> base_{0};  // frame of the heard song where counting started (open / seek)
    std::atomic<int64_t> consumed_{0}; // frames pulled since open/seek
    std::atomic<int> state_{(int) PlayerState::Idle};
    mutable std::mutex errorLock_;
    std::string error_;
};

}  // namespace allmeyn
