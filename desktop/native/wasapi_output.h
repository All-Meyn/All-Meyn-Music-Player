// WASAPI output (Windows). Stage W2: shared mode on the default device, with Windows converting
// the song's rate/format to the mixer's (AUTOCONVERTPCM). Exclusive (bit-perfect) mode is W4.
#pragma once

#include <atomic>
#include <memory>
#include <string>
#include <thread>

#include "core/output.h"

namespace allmeyn {

struct OpenResult;

class WasapiOutput final : public Output {
public:
    WasapiOutput() = default;
    ~WasapiOutput() override;
    bool open(int sampleRate, int channels, PullFn pull, std::string& error) override;
    void setRunning(bool running) override { wantRunning_ = running; }
    int64_t bufferedFrames() const override { return buffered_.load(); }
    std::string description() const override;

private:
    void run(std::shared_ptr<OpenResult> result);

    PullFn pull_;
    int rate_ = 0, channels_ = 0;
    std::thread thread_;
    std::atomic<bool> quit_{false};
    std::atomic<bool> wantRunning_{false};
    std::atomic<int64_t> buffered_{0};
    std::atomic<int> mixRate_{0};
    std::string deviceName_;
};

}  // namespace allmeyn
