// A music file as a stream of interleaved 32-bit float frames at the file's own rate. Float holds
// 16- and 24-bit integer audio exactly, so nothing is lost for those on the way to the output.
#pragma once

#include <cmath>
#include <cstdint>
#include <memory>
#include <string>

namespace allmeyn {

struct SourceInfo {
    int sampleRate = 0;
    int channels = 0;
    int bitsPerSample = 0;    // of the file (16/24/32), 0 if not meaningful (MP3, AAC, Opus...)
    int64_t totalFrames = 0;  // 0 if unknown
    std::string codec;        // "FLAC", "DSD64 (DSF)", ...
    std::string title, artist, album;
    std::string path;         // the file (UTF-8)
    bool lossy = false;       // MP3, AAC, Vorbis, Opus (for the DSP's Air)
    int bitrate = 0;          // bits per second, 0 if unknown (lossy only)
    bool dop = false;         // DSD packed as DoP in 24-bit frames: must reach the DAC bit-exact
    // Native DSD: each frame is one 32-bit word of raw DSD per channel (4 bytes, oldest first, MSB =
    // oldest bit), carried in the float slots as raw bits (only copied, never computed on).
    bool nativeDsd = false;
    int dsdRate = 0;          // DSD bit rate (2822400 for DSD64) when nativeDsd
    float replayGainDb = NAN; // ReplayGain the player applies to this song (NAN = none)
};

/** How a DSF/DFF reaches the output. */
enum class DsdMode { Pcm, Dop, Native };

class Source {
public:
    virtual ~Source() = default;
    const SourceInfo& info() const { return info_; }
    /** Up to [maxFrames] frames into [out] (maxFrames x channels floats). 0 = end, <0 = error. */
    virtual int read(float* out, int maxFrames) = 0;
    /** The next read starts exactly at [frame]. False on error. */
    virtual bool seek(int64_t frame) = 0;

    void setPath(const std::string& p) { info_.path = p; }
    void setReplayGainDb(float db) { info_.replayGainDb = db; }

protected:
    SourceInfo info_;
};

/** Opens [utf8Path]: DSF/DFF with our DSD decoder in [dsd] mode, everything else with FFmpeg. */
std::unique_ptr<Source> openSource(const std::string& utf8Path, std::string& error, DsdMode dsd = DsdMode::Pcm);

/** DSF/DFF (by its first bytes). */
bool isDsdFile(const std::string& utf8Path);

/** Most channels the engine plays (more is refused rather than silently downmixed). */
constexpr int kMaxChannels = 8;

}  // namespace allmeyn
