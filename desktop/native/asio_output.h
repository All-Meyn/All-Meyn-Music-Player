// ASIO output (Windows): the driver gets the song's own rate and its samples untouched.
//  - PCM: converted only to the driver's sample type (bit-perfect when it's as wide as the source).
//  - Native DSD: when the driver takes DSD (kAsioSetIoFormat), raw DSD bytes at the DSD rate.
// One ASIO driver can be open at a time (ASIO callbacks carry no context; there's one instance).
// ASIO is a trademark and software of Steinberg Media Technologies GmbH.
#pragma once

#include <atomic>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "core/dsd_feed.h"
#include "core/output.h"
#include "core/sample_convert.h"

struct IASIO;

namespace allmeyn {

struct AsioOpenResult;

/** Installed ASIO drivers (HKLM\SOFTWARE\ASIO), by name. */
std::vector<std::string> listAsioDrivers();

class AsioOutput final : public Output {
public:
    explicit AsioOutput(std::string driverName) : driverName_(std::move(driverName)) {}
    ~AsioOutput() override;
    bool open(const StreamFormat& format, PullFn pull, std::string& error) override;
    void setRunning(bool running) override { wantRunning_ = running; }
    int64_t bufferedFrames() const override { return buffered_; }
    std::string description() const override { return description_; }

    /** From the driver's thread (bufferSwitch): fill both halves' [index] buffers. */
    void onBufferSwitch(long index);

private:
    void run(std::shared_ptr<AsioOpenResult> result);

    std::string driverName_;
    StreamFormat format_;
    PullFn pull_;
    std::thread thread_;
    std::atomic<bool> quit_{false};
    std::atomic<bool> wantRunning_{false};
    int64_t buffered_ = 0;     // set before open() returns
    std::string description_;  // set before open() returns

    // Set up on the control thread before the driver starts; used by the driver's thread after.
    IASIO* driver_ = nullptr;
    int outChannels_ = 0;      // driver outputs written (2 for mono and stereo sources)
    long bufferSize_ = 0;      // in samples (DSD: bits per channel)
    SampleFormat sample_ = SampleFormat::Float32;
    bool dsdLsbFirst_ = false;
    bool outputReady_ = false;
    std::vector<void*> buffers_[2];  // per output channel, each half
    std::vector<float> scratch_, planar_;
    DsdFeeder feeder_;
};

}  // namespace allmeyn
