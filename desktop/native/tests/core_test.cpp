// Host tests for the desktop engine's core (sources + player), with the Android app's test clips.
// Lossless clips must decode bit-exactly (same SHA-256 of the generated samples as the Android
// emulator test, DecodedPlaybackTest.FFMPEG_CLIP_HASHES) and seek sample-accurately.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "core/player.h"
#include "core/source.h"
#include "core/tags.h"

using namespace allmeyn;

static int failures = 0;
static void check(bool ok, const std::string& what, double v = 0) {
    std::printf("%s  %-66s (%.3f)\n", ok ? "PASS" : "FAIL", what.c_str(), v);
    if (!ok) failures++;
}

// ---- SHA-256 (for comparing with the Android test's hashes) ---------------------------------------
static std::string sha256(const std::vector<uint8_t>& msg) {
    static const uint32_t k[64] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
        0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
        0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
        0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
    uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    std::vector<uint8_t> m = msg;
    const uint64_t bits = (uint64_t) msg.size() * 8;
    m.push_back(0x80);
    while (m.size() % 64 != 56) m.push_back(0);
    for (int i = 7; i >= 0; i--) m.push_back((uint8_t) (bits >> (i * 8)));
    auto rotr = [](uint32_t x, int n) { return (x >> n) | (x << (32 - n)); };
    for (size_t off = 0; off < m.size(); off += 64) {
        uint32_t w[64];
        for (int i = 0; i < 16; i++) w[i] = (uint32_t) m[off + 4 * i] << 24 | (uint32_t) m[off + 4 * i + 1] << 16 | (uint32_t) m[off + 4 * i + 2] << 8 | m[off + 4 * i + 3];
        for (int i = 16; i < 64; i++) {
            const uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            const uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; i++) {
            const uint32_t t1 = hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + k[i] + w[i];
            const uint32_t t2 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    }
    char out[65];
    for (int i = 0; i < 8; i++) std::snprintf(out + 8 * i, 9, "%08x", h[i]);
    return out;
}

static const char* kHash16 = "66d032bd03069e0b2fb00bd8fba8c9939ae12ee2439b0f46c9eda9b9cc962055";
static const char* kHash24 = "f808974ebd2ed8a3a424dd6242551324ae0d5d6a4fd83bf20ea015a7e5d9d3b6";

static std::string asset(const char* name) { return std::string(ALLMEYN_ASSETS) + "/" + name; }

static std::vector<float> decodeAll(Source& s) {
    std::vector<float> all, buf(4096 * (size_t) s.info().channels);
    for (;;) {
        const int n = s.read(buf.data(), 4096);
        if (n <= 0) break;
        all.insert(all.end(), buf.begin(), buf.begin() + (size_t) n * s.info().channels);
    }
    return all;
}

/** The Android test's hash: first 11025 frames as little-endian int32 at [bits]. */
static std::string clipHash(const std::vector<float>& samples, int bits) {
    std::vector<uint8_t> bytes;
    const size_t want = 11025 * 2;
    for (size_t i = 0; i < want && i < samples.size(); i++) {
        const int32_t v = (int32_t) std::lround((double) samples[i] * (1 << (bits - 1)));
        for (int b = 0; b < 4; b++) bytes.push_back((uint8_t) ((uint32_t) v >> (8 * b)));
    }
    return sha256(bytes);
}

/** [hash]: the Android test's SHA-256 for clips made from its generated signal (nullptr: other clips). */
static std::vector<float> testLossless(const char* name, const char* codec, int bits, const char* hash) {
    std::string err;
    auto s = openSource(asset(name), err);
    check(s != nullptr, std::string(name) + ": opens " + err);
    if (!s) return {};
    const auto& i = s->info();
    check(i.codec == codec && i.sampleRate == 44100 && i.channels == 2 && i.bitsPerSample == bits,
          std::string(name) + ": " + i.codec + " 44.1 kHz stereo " + std::to_string(i.bitsPerSample) + "-bit", i.bitsPerSample);
    const auto all = decodeAll(*s);
    const int64_t frames = (int64_t) all.size() / 2;
    check(frames == 88200 && (i.totalFrames == 0 || i.totalFrames == frames), std::string(name) + ": 2 s of frames", (double) frames);
    if (hash) check(clipHash(all, bits) == hash, std::string(name) + ": bit-exact (same SHA-256 as the source)");
    // Sample-accurate seeks, anywhere (incl. the very start and near the end).
    bool exact = true;
    for (int64_t at : {30000LL, 1LL, 0LL, 44100LL, 87000LL, 12345LL}) {
        if (!s->seek(at)) { exact = false; break; }
        std::vector<float> got(1000 * 2);
        const int n = s->read(got.data(), 1000);
        const int expect = (int) std::min<int64_t>(1000, frames - at);
        if (n != expect || memcmp(got.data(), all.data() + at * 2, (size_t) n * 2 * sizeof(float)) != 0) exact = false;
    }
    check(exact, std::string(name) + ": seeks are sample-accurate");
    return all;
}

static void testLossy(const char* name, const char* codec) {
    std::string err;
    auto s = openSource(asset(name), err);
    check(s != nullptr, std::string(name) + ": opens " + err);
    if (!s) return;
    const auto all = decodeAll(*s);
    double sum = 0;
    for (float v : all) sum += (double) v * v;
    const double rms = std::sqrt(sum / std::max<size_t>(1, all.size()));
    const double frames = (double) all.size() / 2;
    check(s->info().codec == codec && s->info().bitsPerSample == 0, std::string(name) + ": " + s->info().codec + ", no bit depth");
    check(std::fabs(frames - 88200) < 3000 && rms > 0.1, std::string(name) + ": ~2 s of signal (frames)", frames);
    check(s->seek(44100) && s->read(std::vector<float>(2000).data(), 1000) == 1000, std::string(name) + ": seeks");
}

/** A DSF file of [seconds] of DSD64 stereo silence (0x69), for the DSD source. */
static std::string writeDsf(double seconds) {
    const std::string path = "/tmp/allmeyn_desktop_test.dsf";
    const uint32_t block = 4096, ch = 2;
    const uint64_t bytesPerCh = (uint64_t) (2822400 * seconds / 8);
    const uint64_t blocks = (bytesPerCh + block - 1) / block;
    const uint64_t dataBytes = blocks * block * ch;
    FILE* f = std::fopen(path.c_str(), "wb");
    auto u32 = [&](uint32_t v) { for (int i = 0; i < 4; i++) std::fputc((int) (v >> (8 * i)) & 0xFF, f); };
    auto u64 = [&](uint64_t v) { for (int i = 0; i < 8; i++) std::fputc((int) (v >> (8 * i)) & 0xFF, f); };
    std::fwrite("DSD ", 1, 4, f); u64(28); u64(28 + 52 + 12 + dataBytes); u64(0);
    std::fwrite("fmt ", 1, 4, f); u64(52); u32(1); u32(0); u32(2); u32(ch); u32(2822400); u32(1);
    u64(bytesPerCh * 8); u32(block); u32(0);
    std::fwrite("data", 1, 4, f); u64(12 + dataBytes);
    std::vector<uint8_t> silence(block, 0x69);
    for (uint64_t b = 0; b < blocks * ch; b++) std::fwrite(silence.data(), 1, block, f);
    std::fclose(f);
    return path;
}

static void testDsd() {
    std::string err;
    auto s = openSource(writeDsf(1.0), err);
    check(s != nullptr, "DSF: opens " + err);
    if (!s) return;
    const auto& i = s->info();
    check(i.sampleRate == 176400 && i.channels == 2 && i.codec.find("DSD64") != std::string::npos,
          "DSF: DSD64 as 176.4 kHz PCM (" + i.codec + ")", i.sampleRate);
    const auto all = decodeAll(*s);
    float peak = 0;
    for (float v : all) peak = std::max(peak, std::fabs(v));
    check(std::fabs((double) all.size() / 2 - 176400) < 200 && peak < 1e-3, "DSF: 1 s, DSD silence decodes to silence (peak)", peak);
    check(s->seek(88200) && s->read(std::vector<float>(2000).data(), 1000) == 1000, "DSF: seeks");
}

// ---- player, driven by a fake output -----------------------------------------------------------------
struct FakeOutput : Output {
    PullFn pull;
    bool open(int, int, PullFn p, std::string&) override { pull = std::move(p); return true; }
    void setRunning(bool) override {}
    int64_t bufferedFrames() const override { return 0; }
    std::string description() const override { return "fake"; }
};

static void testPlayer() {
    FakeOutput* fake = nullptr;
    Player player([&] { auto o = std::make_unique<FakeOutput>(); fake = o.get(); return o; });
    std::string err;
    check(!player.open("/nonexistent/song.flac", err) && !err.empty(), "player: a missing file is refused (" + err + ")");
    check(player.open(asset("clip16.flac"), err) && player.state() == PlayerState::Paused, "player: opens paused " + err);
    std::string e2;
    auto ref = openSource(asset("clip16.flac"), e2);
    const auto all = decodeAll(*ref);

    // Pull until [n] frames arrive (the decoder thread fills asynchronously).
    auto pullFrames = [&](int n) {
        std::vector<float> out;
        std::vector<float> buf(512 * 2);
        for (int tries = 0; (int) out.size() < n * 2 && tries < 4000; tries++) {
            const int got = fake->pull(buf.data(), std::min(512, n - (int) out.size() / 2));
            out.insert(out.end(), buf.begin(), buf.begin() + got * 2);
            if (got == 0) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        return out;
    };
    player.play();
    auto first = pullFrames(5000);
    check(first.size() == 10000 && !memcmp(first.data(), all.data(), first.size() * sizeof(float)), "player: plays from the start, unchanged");
    check(player.positionFrames() == 5000, "player: position follows what was played", (double) player.positionFrames());
    player.seek(40000);
    auto after = pullFrames(3000);
    check(after.size() == 6000 && !memcmp(after.data(), all.data() + 40000 * 2, after.size() * sizeof(float)), "player: seek continues exactly at the target");
    check(player.positionFrames() == 43000, "player: position after seek", (double) player.positionFrames());
    player.seek(10);
    player.seek(60000);  // a second seek before the first is handled must win
    auto second = pullFrames(1000);
    check(second.size() == 2000 && !memcmp(second.data(), all.data() + 60000 * 2, second.size() * sizeof(float)), "player: the latest of two quick seeks wins");
    pullFrames(88200 - 61000);
    std::vector<float> buf(512 * 2);
    for (int i = 0; i < 50 && player.state() != PlayerState::Ended; i++) { fake->pull(buf.data(), 512); std::this_thread::sleep_for(std::chrono::milliseconds(2)); }
    check(player.state() == PlayerState::Ended && player.positionFrames() == 88200, "player: ends at the last frame", (double) player.positionFrames());
    player.play();  // play after the end starts over
    auto again = pullFrames(1000);
    check(player.state() == PlayerState::Playing && again.size() == 2000 && !memcmp(again.data(), all.data(), again.size() * sizeof(float)), "player: play after the end starts over");
    player.close();
    check(player.state() == PlayerState::Idle, "player: closes");
}

// ---- gapless --------------------------------------------------------------------------------------
static void testGapless() {
    FakeOutput* fake = nullptr;
    Player player([&] { auto o = std::make_unique<FakeOutput>(); fake = o.get(); return o; });
    std::string err, e2;
    auto ref16 = openSource(asset("clip16.flac"), e2);
    auto ref24 = openSource(asset("clip24.flac"), e2);
    const auto a = decodeAll(*ref16), b = decodeAll(*ref24);  // both 44.1 kHz stereo: compatible
    auto pullFrames = [&](int n) {
        std::vector<float> out, buf(512 * 2);
        for (int tries = 0; (int) out.size() < n * 2 && tries < 8000; tries++) {
            const int got = fake->pull(buf.data(), std::min(512, n - (int) out.size() / 2));
            out.insert(out.end(), buf.begin(), buf.begin() + got * 2);
            if (got == 0) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        return out;
    };
    check(player.open(asset("clip16.flac"), err) && player.setNext(asset("clip24.flac"), err), "gapless: opens a song and queues the next " + err);
    player.play();
    const int serial0 = player.trackSerial();
    auto first = pullFrames(80000);
    check(player.trackSerial() == serial0 && player.info().bitsPerSample == 16, "gapless: still on the first song before the join");
    auto across = pullFrames(16000);  // 8200 frames of song 1, then 7800 of song 2
    std::vector<float> want(a.begin() + 80000 * 2, a.end());
    want.insert(want.end(), b.begin(), b.begin() + 7800 * 2);
    check(across.size() == want.size() && !memcmp(across.data(), want.data(), want.size() * sizeof(float)),
          "gapless: the join is seamless (last sample of one, first of the next, nothing between)");
    check(player.trackSerial() == serial0 + 1 && player.info().bitsPerSample == 24 && player.positionFrames() == 7800,
          "gapless: now on the second song, position counts from its start", (double) player.positionFrames());

    // Seek while a join is decoded but not yet heard: back on the first song, exactly.
    check(player.open(asset("clip16.flac"), err) && player.setNext(asset("clip24.flac"), err), "gapless: reopen for the seek test");
    player.play();
    pullFrames(87000);  // the decoder is already past the join (1 s ring)
    std::this_thread::sleep_for(std::chrono::milliseconds(30));
    player.seek(20000);
    auto back = pullFrames(2000);
    check(back.size() == 4000 && !memcmp(back.data(), a.data() + 20000 * 2, back.size() * sizeof(float)) && player.info().bitsPerSample == 16,
          "gapless: a seek before the join is heard stays on the heard song");
    pullFrames(88200 - 22000);
    auto next = pullFrames(1000);
    check(!memcmp(next.data(), b.data(), next.size() * sizeof(float)) && player.info().bitsPerSample == 24,
          "gapless: ...and the next song still follows seamlessly");

    // A next song in another format can't join: the player ends, the caller opens it.
    check(player.open(asset("clip16.flac"), err) && player.setNext(writeDsf(0.2), err), "gapless: queue a 176.4 kHz DSF after 44.1 kHz");
    player.play();
    pullFrames(88200);
    std::vector<float> buf(512 * 2);
    for (int i = 0; i < 50 && player.state() != PlayerState::Ended; i++) { fake->pull(buf.data(), 512); std::this_thread::sleep_for(std::chrono::milliseconds(2)); }
    check(player.state() == PlayerState::Ended && player.info().bitsPerSample == 16, "gapless: a different format ends the song instead");
    player.close();
}

// ---- tags -------------------------------------------------------------------------------------------
/** clip.mp3 with an ID3v2.3 tag in front: text frames and an APIC cover ([cover] bytes). */
static std::string writeTaggedMp3(const std::vector<uint8_t>& cover) {
    std::vector<uint8_t> frames;
    auto frame = [&](const char* id, const std::vector<uint8_t>& body) {
        frames.insert(frames.end(), id, id + 4);
        const uint32_t n = (uint32_t) body.size();
        for (int i = 3; i >= 0; i--) frames.push_back((uint8_t) (n >> (8 * i)));
        frames.push_back(0); frames.push_back(0);
        frames.insert(frames.end(), body.begin(), body.end());
    };
    auto text = [&](const char* id, const std::string& v) {
        std::vector<uint8_t> b{3};  // UTF-8
        b.insert(b.end(), v.begin(), v.end());
        frame(id, b);
    };
    text("TIT2", "Hoppípolla");
    text("TPE1", "Sigur Rós");
    text("TALB", "Takk...");
    text("TPE2", "Sigur Rós (album)");
    text("TRCK", "3/12");
    text("TPOS", "1/2");
    text("TYER", "2005");
    std::vector<uint8_t> apic{0};  // Latin-1
    const std::string mime = "image/png";
    apic.insert(apic.end(), mime.begin(), mime.end());
    apic.push_back(0);
    apic.push_back(3);   // front cover
    apic.push_back(0);   // empty description
    apic.insert(apic.end(), cover.begin(), cover.end());
    frame("APIC", apic);
    std::vector<uint8_t> out{'I', 'D', '3', 3, 0, 0};
    const uint32_t size = (uint32_t) frames.size();
    for (int i = 3; i >= 0; i--) out.push_back((uint8_t) ((size >> (7 * i)) & 0x7F));  // syncsafe
    out.insert(out.end(), frames.begin(), frames.end());
    FILE* in = std::fopen(asset("clip.mp3").c_str(), "rb");
    std::vector<uint8_t> mp3(1 << 20);
    mp3.resize(std::fread(mp3.data(), 1, mp3.size(), in));
    std::fclose(in);
    out.insert(out.end(), mp3.begin(), mp3.end());
    const std::string path = "/tmp/allmeyn_tagged.mp3";
    FILE* f = std::fopen(path.c_str(), "wb");
    std::fwrite(out.data(), 1, out.size(), f);
    std::fclose(f);
    return path;
}

static void testTags() {
    struct Want { const char* name; const char* format; int rate; int bits; };
    for (const Want& w : {Want{"clip16.flac", "FLAC", 44100, 16}, Want{"clip24.flac", "FLAC", 44100, 24}, Want{"clip24.m4a", "ALAC", 44100, 24},
                          Want{"clip16.ape", "APE", 44100, 16}, Want{"clip24.wv", "WavPack", 44100, 24}, Want{"clip.mp3", "MP3", 44100, 0},
                          Want{"clip.m4a", "AAC", 44100, 0}}) {
        const TagInfo t = readTags(asset(w.name));
        check(t.ok && t.format == w.format && t.sampleRate == w.rate && t.bits == w.bits && std::abs(t.durationMs - 2000) < 80,
              std::string("tags: ") + w.name + " -> " + t.format + " " + std::to_string(t.bits) + "-bit " + std::to_string(t.durationMs) + " ms" + t.error,
              (double) t.durationMs);
    }
    const TagInfo d = readTags(writeDsf(1.0));
    check(d.ok && d.format == "DSF DSD64" && d.sampleRate == 2822400 && d.bits == 1 && std::abs(d.durationMs - 1000) < 20,
          "tags: DSF -> " + d.format + " " + std::to_string(d.sampleRate) + " Hz, " + std::to_string(d.durationMs) + " ms" + d.error, (double) d.durationMs);
    const std::vector<uint8_t> png = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3, 4, 5, 6, 7, 8};
    const std::string tagged = writeTaggedMp3(png);
    const TagInfo m = readTags(tagged);
    check(m.ok && m.title == "Hoppípolla" && m.artist == "Sigur Rós" && m.album == "Takk..." && m.albumArtist == "Sigur Rós (album)",
          "tags: ID3 title/artist/album/album artist (UTF-8): " + m.title + " / " + m.artist + " / " + m.album + " / " + m.albumArtist);
    check(m.track == 3 && m.disc == 1 && m.year == 2005 && m.hasCover, "tags: track 3/12 -> 3, disc 1/2 -> 1, year, has a cover");
    check(readCover(tagged) == png, "tags: the cover's bytes come back unchanged");
    check(readCover(asset("clip16.flac")).empty() && !readTags("/nonexistent.flac").ok, "tags: no cover / missing file handled");
    check(parseLeadingNumber("03") == 3 && parseLeadingNumber("3/12") == 3 && parseLeadingNumber(" 7 ") == 7 && parseLeadingNumber("A1") == 0 &&
          parseLeadingNumber("") == 0 && parseLeadingNumber("2x") == 0, "tags: number parsing matches Android's");
}

int main() {
    testLossless("clip16.flac", "FLAC", 16, kHash16);
    testLossless("clip24.flac", "FLAC", 24, kHash24);
    testLossless("clip24.m4a", "ALAC", 24, kHash24);
    // The APE (16-bit) and WavPack (24-bit) clips are an older signal of their own: the same one at
    // both depths, so two independent decoders must agree to within the 16-bit version's rounding
    // (measured 1.5 steps, ~-87 dBFS; a decoding fault is off by thousands of steps).
    const auto ape = testLossless("clip16.ape", "APE", 16, nullptr);
    const auto wv = testLossless("clip24.wv", "WavPack", 24, nullptr);
    double maxDiff = ape.size() == wv.size() && !ape.empty() ? 0 : 1;
    for (size_t i = 0; i < std::min(ape.size(), wv.size()); i++) maxDiff = std::fmax(maxDiff, std::fabs((double) ape[i] - wv[i]));
    check(maxDiff <= 2.0 / 32768, "APE 16-bit and WavPack 24-bit decode the same signal (max diff, 16-bit steps)", maxDiff * 32768);
    testLossy("clip.mp3", "MP3");
    testLossy("clip.m4a", "AAC");
    testDsd();
    testPlayer();
    testGapless();
    testTags();
    std::printf(failures ? "%d desktop core test(s) FAILED\n" : "desktop core tests passed\n", failures);
    return failures ? 1 : 0;
}
