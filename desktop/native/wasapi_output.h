// WASAPI output (Windows) on a chosen device.
//  - Exclusive: the song's own rate in the best integer format the device takes (bit-perfect for
//    integer sources); if exclusive isn't available, shared mode instead (unless the stream must be
//    bit-exact, e.g. DoP: then opening fails and the player plays the DSD as PCM).
//  - Shared: Windows converts to its mixer format (AUTOCONVERTPCM).
#pragma once

#include <atomic>
#include <memory>
#include <string>
#include <thread>

#include "core/output.h"
#include "core/sample_convert.h"

namespace allmeyn {

struct OpenResult;

struct WasapiConfig {
    std::string deviceId;   // endpoint ID (UTF-8); empty = the default output
    bool exclusive = true;
};

class WasapiOutput final : public Output {
public:
    explicit WasapiOutput(WasapiConfig config) : config_(std::move(config)) {}
    ~WasapiOutput() override;
    bool open(const StreamFormat& format, PullFn pull, std::string& error) override;
    void setRunning(bool running) override { wantRunning_ = running; }
    int64_t bufferedFrames() const override { return buffered_.load(); }
    std::string description() const override { return description_; }

private:
    void run(std::shared_ptr<OpenResult> result);

    WasapiConfig config_;
    StreamFormat format_;
    PullFn pull_;
    std::thread thread_;
    std::atomic<bool> quit_{false};
    std::atomic<bool> wantRunning_{false};
    std::atomic<int64_t> buffered_{0};
    std::string description_;  // set before open() returns
};

}  // namespace allmeyn
