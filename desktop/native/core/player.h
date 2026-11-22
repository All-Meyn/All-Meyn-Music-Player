// Plays one song: a decode thread fills a ring from the Source, the Output pulls from it.
// Thread-safe for one controlling thread (the UI's JNI calls) plus its own two threads.
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include "output.h"
#include "ring.h"
#include "source.h"

namespace allmeyn {

enum class PlayerState : int { Idle = 0, Playing = 1, Paused = 2, Ended = 3, Error = 4 };

class Player {
public:
    explicit Player(OutputFactory outputs);
    ~Player();

    /** Opens [utf8Path] paused at the start. False with [error] set if it can't be played. */
    bool open(const std::string& utf8Path, std::string& error);
    void play();
    void pause();
    /** Jumps to [frame] (sample-accurate); keeps playing or paused. */
    void seek(int64_t frame);
    void close();

    PlayerState state() const;
    int64_t positionFrames() const;
    SourceInfo info() const;
    std::string error() const;
    std::string outputDescription() const;

    /** The output's pull (public for tests that drive a fake output). */
    int pull(float* out, int frames);

private:
    void decodeLoop();
    void stopThreads();

    OutputFactory outputs_;
    std::unique_ptr<Source> source_;
    std::unique_ptr<Output> output_;
    SourceInfo info_;
    FrameRing ring_;

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

    mutable std::mutex readLock_;      // pull() vs clearing the ring on a seek
    std::atomic<int64_t> base_{0};     // frame the current run started at (open / seek)
    std::atomic<int64_t> consumed_{0}; // frames pulled since then
    std::atomic<int> state_{(int) PlayerState::Idle};
    mutable std::mutex errorLock_;
    std::string error_;
};

}  // namespace allmeyn
