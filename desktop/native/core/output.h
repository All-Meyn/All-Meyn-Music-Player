// Where the player's frames go: WASAPI on Windows, a test harness on Linux.
#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace allmeyn {

/** Fills [frames] interleaved float frames; returns how many it filled (the rest becomes silence). */
using PullFn = std::function<int(float* out, int frames)>;

class Output {
public:
    virtual ~Output() = default;
    /** Prepares a stream at the source's rate/channels; pulling starts with setRunning(true). */
    virtual bool open(int sampleRate, int channels, PullFn pull, std::string& error) = 0;
    virtual void setRunning(bool running) = 0;
    /** Frames pulled but not yet heard (the device buffer), for the position. */
    virtual int64_t bufferedFrames() const = 0;
    /** What the output does to the audio, e.g. "Windows mixer (shared): 48 kHz". */
    virtual std::string description() const = 0;
};

using OutputFactory = std::function<std::unique_ptr<Output>()>;

}  // namespace allmeyn
