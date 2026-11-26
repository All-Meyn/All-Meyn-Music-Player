#include "dsp_stage.h"

namespace allmeyn {

AirHint airHintFor(const SourceInfo& info) {
    if (!info.lossy) return {false, 0};
    if (info.codec == "Opus") return {true, 0};
    if (info.bitrate <= 0) return {true, 16000};
    const int kbps = info.bitrate / 1000 * (info.channels == 1 ? 2 : 1);
    if (kbps <= 64) return {true, 11000};
    if (kbps <= 96) return {true, 14000};
    if (kbps <= 128) return {true, 16000};
    if (kbps <= 192) return {true, 18000};
    return {true, 0};
}

void DspStage::setSettings(const DspSettings& s) {
    std::lock_guard<std::mutex> g(pendingLock_);
    pending_ = s;
    version_++;
    processes_ = s.processes();
}

void DspStage::configure(int sampleRate, int channels, bool pcm, AirHint first) {
    // Lock order everywhere: convLock_, then chainLock_.
    std::lock_guard<std::mutex> cv(convLock_);
    std::lock_guard<std::mutex> c(chainLock_);
    chain_.reset();
    if (pcm && sampleRate > 0 && channels >= 1 && channels <= dsp::kMaxChannels) {
        chain_ = std::make_unique<dsp::Chain>(sampleRate, channels);
    }
    hasChain_ = chain_ != nullptr;
    applied_ = ~0ull;  // the new chain gets the current settings on its first buffer
    if (chain_) {
        // The convolution filter, built for this stream's rate (the output isn't pulling yet).
        if (conv_) chain_->offerConvolution(dsp::buildConvKernel(*conv_, sampleRate));
    }
    {
        std::lock_guard<std::mutex> g(pendingLock_);
        sources_[0] = {0, first};
        sourceCount_ = 1;
        resetPending_ = false;
    }
    std::lock_guard<std::mutex> m(metersLock_);
    metersValid_ = false;
}

void DspStage::setConvolution(std::shared_ptr<const dsp::ConvSource> source) {
    std::lock_guard<std::mutex> cv(convLock_);
    conv_ = std::move(source);
    double rate = 0;
    {
        std::lock_guard<std::mutex> c(chainLock_);
        if (chain_) rate = chain_->sampleRate();
    }
    if (rate <= 0) return;  // no stream: configure() builds it
    dsp::ConvKernel* k = conv_ ? dsp::buildConvKernel(*conv_, rate) : nullptr;  // outside the chain lock
    std::lock_guard<std::mutex> c(chainLock_);
    if (chain_ && chain_->sampleRate() == rate) chain_->offerConvolution(k);
    else dsp::freeConvKernel(k);  // the stream changed meanwhile: configure() built its own
}

void DspStage::release() {
    std::lock_guard<std::mutex> c(chainLock_);
    chain_.reset();
    hasChain_ = false;
    std::lock_guard<std::mutex> m(metersLock_);
    metersValid_ = false;
}

void DspStage::sourceAt(int64_t at, AirHint hint) {
    std::lock_guard<std::mutex> g(pendingLock_);
    if (sourceCount_ == kMaxPendingSources) {  // (never in practice) keep the newest
        for (int i = 1; i < sourceCount_; i++) sources_[(size_t) i - 1] = sources_[(size_t) i];
        sourceCount_--;
    }
    sources_[(size_t) sourceCount_++] = {at, hint};
}

void DspStage::restart(AirHint hint) {
    std::lock_guard<std::mutex> g(pendingLock_);
    sources_[0] = {0, hint};
    sourceCount_ = 1;
    resetPending_ = true;
}

void DspStage::process(float* data, int frames, int64_t firstFrame) {
    std::lock_guard<std::mutex> c(chainLock_);
    if (!chain_) return;
    if (pendingLock_.try_lock()) {
        if (applied_ != version_) {
            settings_ = pending_;
            applied_ = version_;
            chain_->setParams(settings_.params);
        }
        if (resetPending_) {
            chain_->reset();
            resetPending_ = false;
        }
        // Songs starting in or before this buffer (the detector averages over seconds, so a few
        // milliseconds early doesn't matter).
        int used = 0;
        while (used < sourceCount_ && sources_[(size_t) used].at < firstFrame + frames) {
            chain_->setSource(sources_[(size_t) used].hint.lossy, sources_[(size_t) used].hint.priorCutoffHz);
            used++;
        }
        if (used > 0) {
            for (int i = used; i < sourceCount_; i++) sources_[(size_t) (i - used)] = sources_[(size_t) i];
            sourceCount_ -= used;
        }
        pendingLock_.unlock();
    }
    if (!settings_.processes()) {
        if (metersLock_.try_lock()) { metersValid_ = false; metersLock_.unlock(); }
        return;  // untouched
    }
    chain_->process(data, frames);
    if (metersLock_.try_lock()) {
        meters_ = chain_->meters();
        metersValid_ = true;
        metersLock_.unlock();
    }
}

bool DspStage::meters(dsp::Meters& out) const {
    std::lock_guard<std::mutex> m(metersLock_);
    if (!metersValid_ || !running()) return false;
    out = meters_;
    return true;
}

}  // namespace allmeyn
