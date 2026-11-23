#include "player.h"

#include <algorithm>
#include <chrono>
#include <vector>

namespace allmeyn {

namespace {
constexpr double kRingSeconds = 1.0;   // decoded ahead of the output
constexpr int kChunkFrames = 4096;     // decode granularity
}  // namespace

Player::Player(OutputFactory outputs) : outputs_(std::move(outputs)) {}

Player::~Player() { close(); }

bool Player::open(const std::string& path, std::string& error) {
    close();
    // DSD: the best mode the output takes (native, then DoP: both must arrive untouched), else PCM.
    std::vector<DsdMode> modes{DsdMode::Pcm};
    const bool dspOn = dsp_.wantsProcessing();
    if (isDsdFile(path) && !dspOn) {  // (with the DSP on, DSD plays as PCM)
        modes.clear();
        if (nativeAllowed_) modes.push_back(DsdMode::Native);
        if (dopAllowed_) modes.push_back(DsdMode::Dop);
        modes.push_back(DsdMode::Pcm);
    }
    std::unique_ptr<Source> source;
    std::unique_ptr<Output> output;
    for (DsdMode mode : modes) {
        source = openSource(path, error, mode);
        if (!source) return false;
        const SourceInfo& i = source->info();
        // With the DSP on the samples aren't the file's any more: no bit depth to keep (float-like).
        const StreamFormat fmt{i.sampleRate, i.channels, dspOn ? 0 : i.bitsPerSample, i.dop || i.nativeDsd, i.nativeDsd, i.dsdRate};
        output = outputs_();
        if (output->open(fmt, [this](float* out, int n) { return pull(out, n); }, error)) break;
        output.reset();
        if (!fmt.bitExact) return false;  // plain PCM failed: nothing else to try
    }
    if (!output) return false;
    prepareGain(*source);
    const SourceInfo info = source->info();
    ring_.init(std::max(kChunkFrames * 2, (int) (info.sampleRate * kRingSeconds)), info.channels);
    base_ = 0;
    consumed_ = 0;
    written_ = 0;
    sourceEnded_ = false;
    handledGen_ = seekGen_.load();
    quit_ = false;
    streamRate_ = info.sampleRate;
    streamChannels_ = info.channels;
    streamDop_ = info.dop;
    streamNative_ = info.nativeDsd;
    source_ = std::move(source);
    {
        std::lock_guard<std::mutex> g(trackLock_);
        boundaries_.clear();
        heard_ = info;
        trackStart_ = 0;
        reopenHeard_ = false;
    }
    {
        std::lock_guard<std::mutex> g(errorLock_);
        error_.clear();
    }
    dsp_.configure(info.sampleRate, info.channels, !info.dop && !info.nativeDsd, airHintFor(info));
    output_ = std::move(output);
    decoder_ = std::thread(&Player::decodeLoop, this);
    state_ = (int) PlayerState::Paused;
    return true;
}

bool Player::setNext(const std::string& path, std::string& error) {
    auto next = openSource(path, error, bestDsdMode());
    if (!next) return false;
    prepareGain(*next);
    std::lock_guard<std::mutex> g(nextLock_);
    next_ = std::move(next);
    nextReady_ = nextIsCompatible();
    wake_.notify_one();
    return true;
}

void Player::clearNext() {
    std::lock_guard<std::mutex> g(nextLock_);
    next_.reset();
    nextReady_ = false;
}

void Player::prepareGain(Source& s) const {
    const auto mode = (ReplayGainMode) rgMode_.load();
    if (mode == ReplayGainMode::Off || s.info().dop || s.info().nativeDsd) return;
    s.setReplayGainDb(pickGainDb(readReplayGains(s.info().path), mode));
}

bool Player::nextIsCompatible() const {
    return next_ && next_->info().sampleRate == streamRate_.load() && next_->info().channels == streamChannels_.load() &&
           next_->info().dop == streamDop_.load() && next_->info().nativeDsd == streamNative_.load();
}

void Player::play() {
    const auto s = state();
    if (!output_ || s == PlayerState::Error) return;
    if (s == PlayerState::Ended) seek(0);
    state_ = (int) PlayerState::Playing;
    output_->setRunning(true);
}

void Player::pause() {
    if (!output_) return;
    output_->setRunning(false);
    if (state() == PlayerState::Playing) state_ = (int) PlayerState::Paused;
}

void Player::seek(int64_t frame) {
    if (!output_) return;
    advanceHeard();
    {
        std::lock_guard<std::mutex> t(trackLock_);
        if (heard_.totalFrames > 0) frame = std::min(frame, heard_.totalFrames);
        // A join already decoded but not heard yet: the decoder is on the next song; it must go
        // back to the heard one (and keep the next for later).
        if (!boundaries_.empty()) reopenHeard_ = true;
        boundaries_.clear();
        trackStart_ = 0;
        dsp_.restart(airHintFor(heard_));
    }
    frame = std::max<int64_t>(frame, 0);
    {
        // Stop pulling stale audio at once: the output hears silence until the decoder refills.
        std::lock_guard<std::mutex> g(readLock_);
        ring_.clear();
        base_ = frame;
        consumed_ = 0;
        seekTarget_ = frame;
        seekGen_++;
        sourceEnded_ = false;
    }
    if (state() == PlayerState::Ended) state_ = (int) PlayerState::Paused;
    wake_.notify_one();
}

void Player::close() {
    if (output_) output_->setRunning(false);
    output_.reset();  // stops its thread before the sources go
    dsp_.release();
    stopThreads();
    source_.reset();
    clearNext();
    state_ = (int) PlayerState::Idle;
}

void Player::stopThreads() {
    quit_ = true;
    wake_.notify_one();
    if (decoder_.joinable()) decoder_.join();
}

void Player::advanceHeard() const {
    const int64_t buffered = output_ ? output_->bufferedFrames() : 0;
    const int64_t heard = consumed_.load() - buffered;
    std::lock_guard<std::mutex> g(trackLock_);
    while (!boundaries_.empty() && heard >= boundaries_.front().at) {
        trackStart_ = boundaries_.front().at;
        heard_ = boundaries_.front().info;
        base_ = 0;
        boundaries_.pop_front();
        trackSerial_++;
    }
}

PlayerState Player::state() const {
    advanceHeard();
    return (PlayerState) state_.load();
}

int64_t Player::positionFrames() const {
    advanceHeard();
    const int64_t buffered = output_ ? output_->bufferedFrames() : 0;
    std::lock_guard<std::mutex> g(trackLock_);
    const int64_t heard = std::max<int64_t>(0, consumed_.load() - buffered - trackStart_);
    int64_t pos = base_.load() + heard;
    if (heard_.totalFrames > 0) pos = std::min(pos, heard_.totalFrames);
    return pos;
}

SourceInfo Player::info() const {
    advanceHeard();
    std::lock_guard<std::mutex> g(trackLock_);
    return heard_;
}

int Player::trackSerial() const {
    advanceHeard();
    return trackSerial_.load();
}

std::string Player::error() const {
    std::lock_guard<std::mutex> g(errorLock_);
    return error_;
}

std::string Player::outputDescription() const {
    if (!output_) return {};
    std::string d = output_->description();
    if (dsp_.running()) d += " · DSP on";
    return d;
}

int Player::pull(float* out, int frames) {
    std::lock_guard<std::mutex> g(readLock_);
    if (seekPending()) return 0;  // the decoder hasn't jumped yet: silence, not stale audio
    const int n = ring_.read(out, frames);
    if (n > 0) dsp_.process(out, n, consumed_.load());
    consumed_ += n;
    // The end, unless a gapless next song is about to follow.
    if (n < frames && sourceEnded_ && !nextReady_ && ring_.available() == 0 && state_.load() == (int) PlayerState::Playing) {
        state_ = (int) PlayerState::Ended;
    }
    return n;
}

void Player::decodeLoop() {
    std::vector<float> chunk((size_t) kChunkFrames * streamChannels_.load());
    int have = 0, sent = 0;  // decoded frames in chunk / already in the ring
    while (!quit_) {
        if (seekPending()) {
            const uint64_t gen = seekGen_.load();
            bool reopen;
            SourceInfo heard;
            {
                std::lock_guard<std::mutex> t(trackLock_);
                reopen = reopenHeard_;
                reopenHeard_ = false;
                heard = heard_;
            }
            if (reopen) {
                // source_ is already the next song: reopen the heard one and keep this as the next.
                std::string err;
                auto again = openSource(heard.path, err, heard.nativeDsd ? DsdMode::Native : heard.dop ? DsdMode::Dop : DsdMode::Pcm);
                if (again) {
                    again->setReplayGainDb(heard.replayGainDb);
                    source_->seek(0);
                    std::lock_guard<std::mutex> n(nextLock_);
                    if (!next_) next_ = std::move(source_);
                    source_ = std::move(again);
                    nextReady_ = nextIsCompatible();
                }
            }
            const bool ok = source_->seek(seekTarget_);
            have = sent = 0;
            {
                std::lock_guard<std::mutex> g(readLock_);
                ring_.clear();  // anything written before the jump
                written_ = 0;
                // Done only if no newer seek came in meanwhile (else loop and seek again).
                if (seekGen_.load() == gen) handledGen_ = gen;
                sourceEnded_ = false;
            }
            if (!ok) {
                std::lock_guard<std::mutex> g(errorLock_);
                error_ = "Seeking failed";
                state_ = (int) PlayerState::Error;
            }
            continue;
        }
        if (sent < have) {
            const int w = ring_.write(chunk.data() + (size_t) sent * streamChannels_.load(), have - sent);
            sent += w;
            written_ += w;
            if (sent < have) {
                std::unique_lock<std::mutex> l(wakeLock_);
                wake_.wait_for(l, std::chrono::milliseconds(5));
            }
            continue;
        }
        if (sourceEnded_) {
            // Gapless: move straight on to a compatible next song, in the same stream.
            std::unique_ptr<Source> next;
            {
                std::lock_guard<std::mutex> n(nextLock_);
                if (nextReady_) {
                    next = std::move(next_);
                    nextReady_ = false;
                }
            }
            if (next) {
                {
                    std::lock_guard<std::mutex> t(trackLock_);
                    boundaries_.push_back({written_.load(), next->info()});
                }
                dsp_.sourceAt(written_.load(), airHintFor(next->info()));
                source_ = std::move(next);
                sourceEnded_ = false;
                continue;
            }
            std::unique_lock<std::mutex> l(wakeLock_);
            wake_.wait_for(l, std::chrono::milliseconds(20));
            continue;
        }
        const int n = source_->read(chunk.data(), kChunkFrames);
        if (n < 0) {
            std::lock_guard<std::mutex> g(errorLock_);
            error_ = "Decoding failed";
            sourceEnded_ = true;
        } else if (n == 0) {
            sourceEnded_ = true;
        } else {
            // ReplayGain from the song's first sample (PCM only; set by prepareGain).
            const float g = gainFactor(source_->info().replayGainDb);
            if (g < 1.0f) for (size_t k = 0, e = (size_t) n * streamChannels_.load(); k < e; k++) chunk[k] *= g;
            have = n;
            sent = 0;
        }
    }
}

}  // namespace allmeyn
