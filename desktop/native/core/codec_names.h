// Display names for codecs, shared by the player (source.cpp) and the library (tags.cpp).
#pragma once

#include <cstring>
#include <string>

extern "C" {
#include <libavcodec/avcodec.h>
}

namespace allmeyn {

inline std::string codecName(AVCodecID id) {
    switch (id) {
        case AV_CODEC_ID_FLAC: return "FLAC";
        case AV_CODEC_ID_ALAC: return "ALAC";
        case AV_CODEC_ID_APE: return "APE";
        case AV_CODEC_ID_WAVPACK: return "WavPack";
        case AV_CODEC_ID_MP3: return "MP3";
        case AV_CODEC_ID_AAC: return "AAC";
        case AV_CODEC_ID_VORBIS: return "Ogg Vorbis";
        case AV_CODEC_ID_OPUS: return "Opus";
        default: {
            const char* n = avcodec_get_name(id);
            if (n && !strncmp(n, "pcm_", 4)) return "PCM";
            return n ? n : "audio";
        }
    }
}

inline bool isLossyCodec(AVCodecID id) {
    return id == AV_CODEC_ID_MP3 || id == AV_CODEC_ID_AAC || id == AV_CODEC_ID_VORBIS || id == AV_CODEC_ID_OPUS;
}

}  // namespace allmeyn
