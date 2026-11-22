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
    auto source = openSource(path, error);
    if (!source) return false;
    const SourceInfo info = source->info();
    auto output = outputs_();
    ring_.init(std::max(kChunkFrames * 2, (int) (info.sampleRate * kRingSeconds)), info.channels);
    base_ = 0;
    consumed_ = 0;
    sourceEnded_ = false;
    handledGen_ = seekGen_.load();
    quit_ = false;
    source_ = std::move(source);
    info_ = info;
    {
        std::lock_guard<std::mutex> g(errorLock_);
        error_.clear();
    }
    decoder_ = std::thread(&Player::decodeLoop, this);
    if (!output->open(info.sampleRate, info.channels, [this](float* out, int n) { return pull(out, n); }, error)) {
        stopThreads();
        source_.reset();
        return false;
    }
    output_ = std::move(output);
    state_ = (int) PlayerState::Paused;
    return true;
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
    if (!source_) return;
    if (info_.totalFrames > 0) frame = std::min(frame, info_.totalFrames);
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
    output_.reset();  // stops its thread before the source goes
    stopThreads();
    source_.reset();
    state_ = (int) PlayerState::Idle;
}

void Player::stopThreads() {
    quit_ = true;
    wake_.notify_one();
    if (decoder_.joinable()) decoder_.join();
}

PlayerState Player::state() const { return (PlayerState) state_.load(); }

int64_t Player::positionFrames() const {
    const int64_t buffered = output_ ? output_->bufferedFrames() : 0;
    const int64_t heard = std::max<int64_t>(0, consumed_.load() - buffered);
    int64_t pos = base_.load() + heard;
    if (info_.totalFrames > 0) pos = std::min(pos, info_.totalFrames);
    return pos;
}

SourceInfo Player::info() const { return info_; }

std::string Player::error() const {
    std::lock_guard<std::mutex> g(errorLock_);
    return error_;
}

std::string Player::outputDescription() const { return output_ ? output_->description() : std::string(); }

int Player::pull(float* out, int frames) {
    std::lock_guard<std::mutex> g(readLock_);
    if (seekPending()) return 0;  // the decoder hasn't jumped yet: silence, not stale audio
    const int n = ring_.read(out, frames);
    consumed_ += n;
    if (n < frames && sourceEnded_ && ring_.available() == 0 && state() == PlayerState::Playing) {
        state_ = (int) PlayerState::Ended;
    }
    return n;
}

void Player::decodeLoop() {
    std::vector<float> chunk((size_t) kChunkFrames * info_.channels);
    int have = 0, sent = 0;  // decoded frames in chunk / already in the ring
    while (!quit_) {
        if (seekPending()) {
            const uint64_t gen = seekGen_.load();
            const bool ok = source_->seek(seekTarget_);
            have = sent = 0;
            {
                std::lock_guard<std::mutex> g(readLock_);
                ring_.clear();  // anything written before the jump
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
            sent += ring_.write(chunk.data() + (size_t) sent * info_.channels, have - sent);
            if (sent < have) {
                std::unique_lock<std::mutex> l(wakeLock_);
                wake_.wait_for(l, std::chrono::milliseconds(5));
            }
            continue;
        }
        if (sourceEnded_) {
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
            have = n;
            sent = 0;
        }
    }
}

}  // namespace allmeyn
