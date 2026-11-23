// ReplayGain, as in the Android app (playback/ReplayGain.kt): evens out loudness by scaling a song's
// samples down by the gain in its tags. Only lowers volume (never boosts, can't clip). The player
// applies it to PCM only, from each song's first sample; never to DoP / native DSD. The app turns it
// off for bit-perfect outputs (exclusive WASAPI, ASIO).
#pragma once

#include <cmath>
#include <string>

namespace allmeyn {

enum class ReplayGainMode { Off = 0, Track = 1, Album = 2 };

struct ReplayGains {
    float trackDb = NAN, albumDb = NAN;  // NAN = no tag
};

/** "-6.20 dB", "+1.5", "-6,2 dB" -> dB; NAN if unreadable or outside -60..60. */
float parseGainDb(const std::string& s);

/** Opus R128_*_GAIN (Q7.8 dB relative to -23 LUFS) in ReplayGain terms (+5 dB, its -18 LUFS reference). */
float r128ToReplayGainDb(const std::string& s);

/** The gain to use: album gain in album mode (track gain if missing), and vice versa. NAN = none. */
inline float pickGainDb(const ReplayGains& g, ReplayGainMode m) {
    switch (m) {
        case ReplayGainMode::Track: return std::isnan(g.trackDb) ? g.albumDb : g.trackDb;
        case ReplayGainMode::Album: return std::isnan(g.albumDb) ? g.trackDb : g.albumDb;
        default: return NAN;
    }
}

/** Linear factor for [db]: 10^(dB/20), at most 1. NAN = 1. */
inline float gainFactor(float db) {
    if (std::isnan(db)) return 1.0f;
    const float g = std::pow(10.0f, db / 20.0f);
    return g < 1.0f ? (g > 0.0f ? g : 0.0f) : 1.0f;
}

/** The file's ReplayGain tags (Vorbis comments, ID3 TXXX incl. DSF, APEv2, MP4 freeform, Opus R128). */
ReplayGains readReplayGains(const std::string& utf8Path);

}  // namespace allmeyn
