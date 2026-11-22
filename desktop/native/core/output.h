// Where the player's frames go: WASAPI on Windows, a test harness on Linux.
#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace allmeyn {

/** Fills [frames] interleaved float frames; returns how many it filled (the rest becomes silence). */
using PullFn = std::function<int(float* out, int frames)>;

/** What the player sends. */
struct StreamFormat {
    int sampleRate = 0;
    int channels = 0;
    int bits = 0;           // the source's bit depth (16/24/32), 0 if not meaningful (lossy, DSD as PCM)
    bool bitExact = false;  // must reach the DAC unchanged (DoP): fail rather than convert or mix
};

class Output {
public:
    virtual ~Output() = default;
    /** Prepares a stream; pulling starts with setRunning(true). */
    virtual bool open(const StreamFormat& format, PullFn pull, std::string& error) = 0;
    virtual void setRunning(bool running) = 0;
    /** Frames pulled but not yet heard (the device buffer), for the position. */
    virtual int64_t bufferedFrames() const = 0;
    /** What the output does to the audio, e.g. "Windows mixer (shared): 48 kHz". */
    virtual std::string description() const = 0;
};

using OutputFactory = std::function<std::unique_ptr<Output>()>;

}  // namespace allmeyn
