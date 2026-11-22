// Tags, duration and format of a music file, for the library (no decoding).
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace allmeyn {

struct TagInfo {
    bool ok = false;
    std::string error;
    std::string title, artist, album, albumArtist;
    int track = 0, disc = 0, year = 0;
    int64_t durationMs = 0;
    std::string format;   // as the Android library labels it: "FLAC", "ALAC", "MP3", "DSF DSD64", ...
    int sampleRate = 0;   // Hz; DSD: the DSD rate (2822400 for DSD64), as on Android
    int bits = 0;         // 0 if not meaningful (lossy); 1 for DSD, as on Android
    bool hasCover = false;
};

TagInfo readTags(const std::string& utf8Path);

/** The embedded cover picture (JPEG/PNG bytes as stored), empty if none. */
std::vector<uint8_t> readCover(const std::string& utf8Path);

/** "3", "03", "3/12" -> 3; anything else 0 (same rule as the Android LibraryIndex.parseNumber). */
int parseLeadingNumber(const std::string& s);

}  // namespace allmeyn
