#include "source.h"

#include <algorithm>
#include <cstring>
#include <vector>

#include "../platform/file_open.h"
#include "codec_names.h"
#include "sample_convert.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/log.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>

#include "decoder.h"  // app/src/main/cpp/decoder: the Android app's DSD decoder (dsd.c)
}

namespace allmeyn {
namespace {

std::string ffError(int rc) {
    char buf[128];
    av_strerror(rc, buf, sizeof buf);
    return buf;
}

std::string tag(AVDictionary* a, AVDictionary* b, const char* key) {
    for (AVDictionary* d : {a, b}) {
        if (const AVDictionaryEntry* e = av_dict_get(d, key, nullptr, 0); e && e->value && *e->value) return e->value;
    }
    return {};
}

// ---- FFmpeg ----------------------------------------------------------------------------------

class FfmpegSource final : public Source {
public:
    ~FfmpegSource() override {
        av_frame_free(&frame_);
        av_packet_free(&packet_);
        swr_free(&swr_);
        avcodec_free_context(&codec_);
        avformat_close_input(&format_);
    }

    bool open(const std::string& path, std::string& error) {
        int rc = avformat_open_input(&format_, path.c_str(), nullptr, nullptr);
        if (rc < 0) { error = "Can't open the file: " + ffError(rc); return false; }
        if ((rc = avformat_find_stream_info(format_, nullptr)) < 0) { error = "Can't read the file: " + ffError(rc); return false; }
        const AVCodec* dec = nullptr;
        stream_ = av_find_best_stream(format_, AVMEDIA_TYPE_AUDIO, -1, -1, &dec, 0);
        if (stream_ < 0 || !dec) { error = "No playable audio in this file"; return false; }
        AVStream* st = format_->streams[stream_];
        codec_ = avcodec_alloc_context3(dec);
        if (!codec_ || avcodec_parameters_to_context(codec_, st->codecpar) < 0) { error = "Decoder setup failed"; return false; }
        if ((rc = avcodec_open2(codec_, dec, nullptr)) < 0) { error = "Can't decode this format: " + ffError(rc); return false; }
        const int channels = codec_->ch_layout.nb_channels;
        if (channels < 1 || channels > kMaxChannels || codec_->sample_rate <= 0) { error = "Unsupported channel count or rate"; return false; }
        // Discard everything but the audio stream we play (cover art, other tracks).
        for (unsigned i = 0; i < format_->nb_streams; i++) if ((int) i != stream_) format_->streams[i]->discard = AVDISCARD_ALL;

        // Format conversion only (planar/integer -> interleaved float), never resampling.
        rc = swr_alloc_set_opts2(&swr_, &codec_->ch_layout, AV_SAMPLE_FMT_FLT, codec_->sample_rate,
                                 &codec_->ch_layout, codec_->sample_fmt, codec_->sample_rate, 0, nullptr);
        if (rc < 0 || swr_init(swr_) < 0) { error = "Sample format conversion setup failed"; return false; }

        frame_ = av_frame_alloc();
        packet_ = av_packet_alloc();
        timeBase_ = st->time_base;
        info_.sampleRate = codec_->sample_rate;
        info_.channels = channels;
        info_.bitsPerSample = st->codecpar->bits_per_raw_sample > 0 ? st->codecpar->bits_per_raw_sample
                              : (av_get_bytes_per_sample(codec_->sample_fmt) > 0 && !av_sample_fmt_is_planar(codec_->sample_fmt) &&
                                 codec_->sample_fmt != AV_SAMPLE_FMT_FLT && codec_->sample_fmt != AV_SAMPLE_FMT_FLTP
                                     ? av_get_bytes_per_sample(codec_->sample_fmt) * 8 : 0);
        if (isLossyCodec(codec_->codec_id)) info_.bitsPerSample = 0;
        if (st->duration != AV_NOPTS_VALUE && st->duration > 0) {
            info_.totalFrames = av_rescale_q(st->duration, timeBase_, AVRational{1, info_.sampleRate});
        } else if (format_->duration > 0) {
            info_.totalFrames = av_rescale(format_->duration, info_.sampleRate, AV_TIME_BASE);
        }
        info_.codec = codecName(codec_->codec_id);
        info_.title = tag(format_->metadata, st->metadata, "title");
        info_.artist = tag(format_->metadata, st->metadata, "artist");
        info_.album = tag(format_->metadata, st->metadata, "album");
        return true;
    }

    int read(float* out, int maxFrames) override {
        int written = 0;
        while (written < maxFrames) {
            if (pendingPos_ < (int) pending_.size() / info_.channels) {
                const int n = std::min(maxFrames - written, (int) pending_.size() / info_.channels - pendingPos_);
                memcpy(out + (size_t) written * info_.channels, pending_.data() + (size_t) pendingPos_ * info_.channels,
                       (size_t) n * info_.channels * sizeof(float));
                pendingPos_ += n;
                written += n;
                continue;
            }
            const int got = decodeNext();
            if (got < 0) return written > 0 ? written : got;
            if (got == 0) break;  // end of file
        }
        return written;
    }

    bool seek(int64_t target) override {
        if (target < 0) target = 0;
        const int64_t ts = av_rescale_q(target, AVRational{1, info_.sampleRate}, timeBase_);
        if (av_seek_frame(format_, stream_, ts, AVSEEK_FLAG_BACKWARD) < 0 &&
            av_seek_frame(format_, stream_, 0, AVSEEK_FLAG_BACKWARD | AVSEEK_FLAG_ANY) < 0) {
            return false;
        }
        avcodec_flush_buffers(codec_);
        pending_.clear();
        pendingPos_ = 0;
        eof_ = false;
        skipTo_ = target;  // decoded frames before the target are dropped (sample-accurate seek)
        nextFrame_ = -1;
        return true;
    }

private:
    /** Decodes the next frame into pending_. Frames decoded, 0 at the end, <0 on error. */
    int decodeNext() {
        for (;;) {
            int rc = avcodec_receive_frame(codec_, frame_);
            if (rc == 0) {
                const int n = convert();
                av_frame_unref(frame_);
                if (n > 0) return n;
                continue;  // all of it was before the seek target
            }
            if (rc == AVERROR_EOF) return 0;
            if (rc != AVERROR(EAGAIN)) return -1;
            if (eof_) return 0;
            rc = av_read_frame(format_, packet_);
            if (rc < 0) {
                eof_ = true;
                avcodec_send_packet(codec_, nullptr);  // drain
                continue;
            }
            if (packet_->stream_index == stream_) avcodec_send_packet(codec_, packet_);
            av_packet_unref(packet_);
        }
    }

    /** frame_ -> pending_ as interleaved float, dropping samples before skipTo_. */
    int convert() {
        const int ch = info_.channels;
        const int n = frame_->nb_samples;
        pending_.resize((size_t) n * ch);
        uint8_t* outPlanes[1] = {reinterpret_cast<uint8_t*>(pending_.data())};
        const int got = swr_convert(swr_, outPlanes, n, (const uint8_t**) frame_->extended_data, n);
        if (got <= 0) { pending_.clear(); pendingPos_ = 0; return 0; }
        pending_.resize((size_t) got * ch);
        // Where this frame starts, in samples: from its timestamp after a seek, then counted on.
        int64_t start = nextFrame_;
        const int64_t pts = frame_->best_effort_timestamp;
        if (start < 0) start = pts != AV_NOPTS_VALUE ? av_rescale_q(pts, timeBase_, AVRational{1, info_.sampleRate}) : 0;
        nextFrame_ = start + got;
        pendingPos_ = 0;
        if (skipTo_ > start) {
            const int64_t drop = std::min<int64_t>(skipTo_ - start, got);
            pendingPos_ = (int) drop;
            if (drop >= got) return 0;
        }
        skipTo_ = -1;
        return got - pendingPos_;
    }

    AVFormatContext* format_ = nullptr;
    AVCodecContext* codec_ = nullptr;
    SwrContext* swr_ = nullptr;
    AVFrame* frame_ = nullptr;
    AVPacket* packet_ = nullptr;
    AVRational timeBase_{1, 1};
    int stream_ = -1;
    bool eof_ = false;
    std::vector<float> pending_;
    int pendingPos_ = 0;
    int64_t skipTo_ = -1;     // drop decoded samples before this frame (after a seek)
    int64_t nextFrame_ = 0;   // sample index of the next decoded frame; -1 = take it from its pts
};

// ---- DSD (DSF/DFF), via the Android app's decoder, as PCM ----------------------------------------

class DsdSource final : public Source {
public:
    explicit DsdSource(Decoder* d) : d_(d) {
        info_.sampleRate = d->info.sample_rate;
        info_.channels = d->info.channels;
        info_.dop = d->info.is_dop != 0;
        info_.nativeDsd = d->info.is_native != 0;
        info_.bitsPerSample = info_.dop ? 24 : (info_.nativeDsd ? 1 : 0);
        if (info_.nativeDsd) {
            info_.channels = d->info.channels / 2;  // the decoder counts 16-bit halves of each 32-bit word
            info_.dsdRate = d->info.sample_rate * 32;
        }
        info_.totalFrames = d->info.total_frames;
        info_.codec = d->info.desc;  // e.g. "DSF DSD64 → PCM 176.4 kHz" / "DSF DSD64 → DoP 176.4 kHz ..."
    }
    ~DsdSource() override { d_->close(d_); }
    int read(float* out, int maxFrames) override {
        // PCM is float already; native DSD is raw words (4 bytes = one float slot per channel).
        if (!info_.dop) return d_->read(d_, reinterpret_cast<uint8_t*>(out), maxFrames);
        // DoP: packed 24-bit words -> float, exactly (the output turns them back into the same words).
        packed_.resize((size_t) maxFrames * info_.channels * 3);
        const int n = d_->read(d_, packed_.data(), maxFrames);
        for (int i = 0; i < n * info_.channels; i++) out[i] = int24ToFloat(packed_.data() + 3 * i);
        return n;
    }
    bool seek(int64_t frame) override { return d_->seek(d_, frame) == 0; }

private:
    Decoder* d_;
    std::vector<uint8_t> packed_;
};

bool looksLikeDsd(const std::string& path) {
    const int fd = openForRead(path);
    if (fd < 0) return false;
    char magic[4] = {};
    FILE* f = fdopen(fd, "rb");
    if (!f) return false;
    const bool ok = fread(magic, 1, 4, f) == 4 && (!memcmp(magic, "DSD ", 4) || !memcmp(magic, "FRM8", 4));
    fclose(f);
    return ok;
}

}  // namespace

bool isDsdFile(const std::string& path) { return looksLikeDsd(path); }

std::unique_ptr<Source> openSource(const std::string& path, std::string& error, DsdMode dsd) {
    av_log_set_level(AV_LOG_ERROR);  // FFmpeg's notes on odd files aren't errors; keep stderr quiet
    if (looksLikeDsd(path)) {
        const int fd = openForRead(path);
        if (fd < 0) { error = "Can't open the file"; return nullptr; }
        char err[160] = {};
        // DoP / native only when the output takes them bit-exact (the player decides). Native needs
        // stereo; the decoder falls back to PCM otherwise.
        Decoder* d = dsd_open(fd, dsd == DsdMode::Dop ? 1 : 0, dsd == DsdMode::Native ? 0xFFFFFFFFu : 0, err, sizeof err);
        if (!d) { error = err[0] ? err : "Not a playable DSD file"; return nullptr; }
        const int expect = d->info.is_native ? DEC_S16 : d->info.is_dop ? DEC_S24 : DEC_F32;
        if (d->info.format != expect || d->info.channels < 1 || d->info.channels > 2 * kMaxChannels) {
            d->close(d);
            error = "Unsupported DSD layout";
            return nullptr;
        }
        auto s = std::make_unique<DsdSource>(d);
        s->setPath(path);
        return s;
    }
    auto s = std::make_unique<FfmpegSource>();
    if (!s->open(path, error)) return nullptr;
    s->setPath(path);
    return s;
}

}  // namespace allmeyn
