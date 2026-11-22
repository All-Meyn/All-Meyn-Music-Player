#include "tags.h"

#include <cctype>
#include <cstring>

#include "codec_names.h"

extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/log.h>
}

namespace allmeyn {
namespace {

/** First non-empty value of any of [keys], in the container's then the stream's metadata. */
std::string tag(AVFormatContext* f, AVStream* st, std::initializer_list<const char*> keys) {
    for (const char* k : keys) {
        for (AVDictionary* d : {f->metadata, st ? st->metadata : nullptr}) {
            if (const AVDictionaryEntry* e = av_dict_get(d, k, nullptr, 0); e && e->value && *e->value) return e->value;
        }
    }
    return {};
}

/** Opens [path] with FFmpeg's demuxers only (enough for tags, duration and the cover). */
AVFormatContext* openFormat(const std::string& path, std::string& error) {
    av_log_set_level(AV_LOG_ERROR);
    AVFormatContext* f = nullptr;
    AVDictionary* opts = nullptr;
    av_dict_set(&opts, "probesize", "1000000", 0);  // a library scan reads many files: keep probing short
    const int rc = avformat_open_input(&f, path.c_str(), nullptr, &opts);
    av_dict_free(&opts);
    if (rc < 0) {
        char buf[128];
        av_strerror(rc, buf, sizeof buf);
        error = buf;
        return nullptr;
    }
    return f;
}

}  // namespace

int parseLeadingNumber(const std::string& s) {
    size_t i = 0;
    while (i < s.size() && std::isspace((unsigned char) s[i])) i++;
    int v = 0;
    bool any = false;
    while (i < s.size() && std::isdigit((unsigned char) s[i]) && v < 100000) { v = v * 10 + (s[i] - '0'); i++; any = true; }
    while (i < s.size() && std::isspace((unsigned char) s[i])) i++;
    if (!any || (i < s.size() && s[i] != '/')) return 0;
    return v;
}

TagInfo readTags(const std::string& path) {
    TagInfo t;
    AVFormatContext* f = openFormat(path, t.error);
    if (!f) return t;
    if (avformat_find_stream_info(f, nullptr) < 0) {
        t.error = "unreadable";
        avformat_close_input(&f);
        return t;
    }
    const int si = av_find_best_stream(f, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    AVStream* st = si >= 0 ? f->streams[si] : nullptr;
    if (!st) {
        t.error = "no audio";
        avformat_close_input(&f);
        return t;
    }
    const AVCodecParameters* p = st->codecpar;
    t.title = tag(f, st, {"title"});
    t.artist = tag(f, st, {"artist"});
    t.album = tag(f, st, {"album"});
    t.albumArtist = tag(f, st, {"album_artist", "albumartist", "album artist"});
    t.track = parseLeadingNumber(tag(f, st, {"track", "tracknumber"}));
    t.disc = parseLeadingNumber(tag(f, st, {"disc", "discnumber"}));
    const std::string date = tag(f, st, {"date", "year", "originaldate"});
    t.year = date.size() >= 4 ? parseLeadingNumber(date.substr(0, 4)) : 0;

    if (st->duration != AV_NOPTS_VALUE && st->duration > 0) {
        t.durationMs = av_rescale_q(st->duration, st->time_base, AVRational{1, 1000});
    } else if (f->duration > 0) {
        t.durationMs = f->duration / 1000;
    }

    const bool dsd = p->codec_id == AV_CODEC_ID_DSD_LSBF || p->codec_id == AV_CODEC_ID_DSD_MSBF ||
                     p->codec_id == AV_CODEC_ID_DSD_LSBF_PLANAR || p->codec_id == AV_CODEC_ID_DSD_MSBF_PLANAR;
    if (dsd) {
        // FFmpeg reports DSD as bytes per second per channel: x8 is the DSD rate.
        t.sampleRate = p->sample_rate * 8;
        const int base = t.sampleRate % 44100 == 0 ? 2822400 : 3072000;
        const char* container = !strcmp(f->iformat->name, "dsf") ? "DSF" : "DFF";
        t.format = std::string(container) + " DSD" + std::to_string(64 * (t.sampleRate / base));
        t.bits = 1;
    } else {
        t.sampleRate = p->sample_rate;
        t.format = codecName(p->codec_id);
        t.bits = isLossyCodec(p->codec_id) ? 0 : (p->bits_per_raw_sample > 0 ? p->bits_per_raw_sample : p->bits_per_coded_sample);
    }
    for (unsigned i = 0; i < f->nb_streams; i++) {
        if (f->streams[i]->disposition & AV_DISPOSITION_ATTACHED_PIC && f->streams[i]->attached_pic.size > 0) t.hasCover = true;
    }
    t.ok = true;
    avformat_close_input(&f);
    return t;
}

std::vector<uint8_t> readCover(const std::string& path) {
    std::string error;
    AVFormatContext* f = openFormat(path, error);
    std::vector<uint8_t> out;
    if (!f) return out;
    // Attached pictures are read with the header; no stream info needed.
    for (unsigned i = 0; i < f->nb_streams; i++) {
        const AVStream* st = f->streams[i];
        if (st->disposition & AV_DISPOSITION_ATTACHED_PIC && st->attached_pic.size > 0) {
            out.assign(st->attached_pic.data, st->attached_pic.data + st->attached_pic.size);
            break;
        }
    }
    avformat_close_input(&f);
    return out;
}

}  // namespace allmeyn
