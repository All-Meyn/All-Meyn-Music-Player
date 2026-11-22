// Host tests for the desktop engine's core (sources + player), with the Android app's test clips.
// Lossless clips must decode bit-exactly (same SHA-256 of the generated samples as the Android
// emulator test, DecodedPlaybackTest.FFMPEG_CLIP_HASHES) and seek sample-accurately.
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "core/player.h"
#include "core/dsd_feed.h"
#include "core/sample_convert.h"
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
    StreamFormat format;
    bool refuseBitExact = false;  // like shared mode / a busy device
    bool open(const StreamFormat& f, PullFn p, std::string& err) override {
        if (f.bitExact && refuseBitExact) { err = "not bit-exact"; return false; }
        format = f;
        pull = std::move(p);
        return true;
    }
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

// ---- bit-perfect output and DoP ----------------------------------------------------------------------
static void testConversion() {
    // Every 16-bit value survives int -> float -> 16-bit, and -> 32-bit as the same value shifted.
    bool ok16 = true, ok16in32 = true;
    for (int s = -32768; s <= 32767; s++) {
        const float f = (float) s / 32768.0f;
        int16_t a; int32_t b;
        convertFromFloat(&f, reinterpret_cast<uint8_t*>(&a), 1, SampleFormat::Int16);
        convertFromFloat(&f, reinterpret_cast<uint8_t*>(&b), 1, SampleFormat::Int32);
        ok16 &= a == s;
        ok16in32 &= b == s * 65536;
    }
    check(ok16 && ok16in32, "convert: every 16-bit sample exact (16-bit and 32-bit containers)");
    // Every 24-bit value survives to 24-in-32 and packed 24 (incl. DoP words like 0x05xxxx / 0xFAxxxx).
    bool ok24 = true;
    for (int s = -8388608; s <= 8388607; s += 7) {
        const uint8_t in[3] = {(uint8_t) s, (uint8_t) (s >> 8), (uint8_t) (s >> 16)};
        const float f = int24ToFloat(in);
        int32_t w; uint8_t p[3];
        convertFromFloat(&f, reinterpret_cast<uint8_t*>(&w), 1, SampleFormat::Int24In32);
        convertFromFloat(&f, p, 1, SampleFormat::Int24Packed);
        ok24 &= w == (int32_t) ((uint32_t) s << 8) && !memcmp(p, in, 3);
    }
    check(ok24, "convert: 24-bit samples exact (24-in-32 and packed)");
    const float hot[2] = {1.5f, -1.5f};
    int16_t c[2];
    convertFromFloat(hot, reinterpret_cast<uint8_t*>(c), 2, SampleFormat::Int16);
    check(c[0] == 32767 && c[1] == -32768, "convert: out-of-range floats clip instead of wrapping");
}

static void testDop() {
    std::string err;
    const std::string dsf = writeDsf(0.5);
    auto s = openSource(dsf, err, DsdMode::Dop);
    check(s && s->info().dop && s->info().sampleRate == 176400 && s->info().bitsPerSample == 24, "DoP: DSD64 opens as 176.4 kHz 24-bit DoP " + err);
    if (!s) return;
    std::vector<float> f(1000 * 2);
    const int n = s->read(f.data(), 1000);
    std::vector<uint8_t> words((size_t) n * 2 * 3);
    convertFromFloat(f.data(), words.data(), (size_t) n * 2, SampleFormat::Int24Packed);
    bool markers = true, idle = true;
    for (int i = 0; i < n; i++) {
        const uint8_t want = (i & 1) ? 0xFA : 0x05;
        for (int c = 0; c < 2; c++) {
            const uint8_t* w = &words[((size_t) i * 2 + c) * 3];
            markers &= w[2] == want;
            // DSF stores DSD LSB-first, DoP carries it MSB-first: silence 0x69 arrives bit-reversed, 0x96.
            idle &= w[0] == 0x96 && w[1] == 0x96;
        }
    }
    check(n == 1000 && markers && idle, "DoP: markers alternate 0x05/0xFA and the DSD bytes arrive intact");

    // The player asks for a bit-exact stream for DoP; refused (shared mode / busy) -> PCM instead.
    FakeOutput* fake = nullptr;
    bool refuse = false;
    Player player([&] { auto o = std::make_unique<FakeOutput>(); o->refuseBitExact = refuse; fake = o.get(); return o; });
    player.setDopAllowed(true);
    check(player.open(dsf, err) && player.info().dop && fake->format.bitExact && fake->format.bits == 24,
          "DoP: player opens DSD as DoP and requires a bit-exact output");
    refuse = true;
    check(player.open(dsf, err) && !player.info().dop && !fake->format.bitExact && player.info().sampleRate == 176400,
          "DoP: an output that can't be bit-exact gets PCM instead (never DoP into a mixer)");
    player.setDopAllowed(false);
    refuse = false;
    check(player.open(dsf, err) && !player.info().dop, "DoP: off -> DSD plays as PCM");
    check(player.open(asset("clip24.flac"), err) && fake->format.bits == 24 && !fake->format.bitExact, "output: a 24-bit FLAC asks for 24 bits");
    player.close();
}

// ---- native DSD (ASIO) -----------------------------------------------------------------------------
static void testNativeDsd() {
    std::string err;
    // A DSF with a recognisable pattern: channel 0 bytes 0,1,2,... channel 1 bytes 255,254,...
    const std::string path = "/tmp/allmeyn_native.dsf";
    {
        const uint32_t block = 4096;
        const uint64_t bytesPerCh = 2 * block;
        FILE* f = std::fopen(path.c_str(), "wb");
        auto u32 = [&](uint32_t v) { for (int i = 0; i < 4; i++) std::fputc((int) (v >> (8 * i)) & 0xFF, f); };
        auto u64 = [&](uint64_t v) { for (int i = 0; i < 8; i++) std::fputc((int) (v >> (8 * i)) & 0xFF, f); };
        std::fwrite("DSD ", 1, 4, f); u64(28); u64(28 + 52 + 12 + bytesPerCh * 2); u64(0);
        std::fwrite("fmt ", 1, 4, f); u64(52); u32(1); u32(0); u32(2); u32(2); u32(2822400); u32(1);
        u64(bytesPerCh * 8); u32(block); u32(0);
        std::fwrite("data", 1, 4, f); u64(12 + bytesPerCh * 2);
        for (uint64_t b = 0; b < 2; b++)
            for (int c = 0; c < 2; c++)
                for (uint32_t i = 0; i < block; i++) std::fputc(c == 0 ? (int) ((b * block + i) & 0xFF) : (int) (255 - ((b * block + i) & 0xFF)), f);
        std::fclose(f);
    }
    auto s = openSource(path, err, DsdMode::Native);
    check(s && s->info().nativeDsd && s->info().channels == 2 && s->info().dsdRate == 2822400 && s->info().sampleRate == 88200,
          "native DSD: DSD64 opens as raw words, 2 channels, 88.2k words/s " + err);
    if (!s) return;
    std::vector<float> words(2048 * 2);
    const int n = s->read(words.data(), 2048);
    const uint8_t* raw = reinterpret_cast<const uint8_t*>(words.data());
    bool ordered = n == 2048;
    for (int f = 0; f < n && ordered; f++)
        for (int k = 0; k < 4; k++) {
            // DSF is LSB-first: the decoder bit-reverses into MSB-first words, oldest byte first.
            const uint8_t want0 = reverseBits((uint8_t) ((f * 4 + k) & 0xFF)), want1 = reverseBits((uint8_t) (255 - ((f * 4 + k) & 0xFF)));
            ordered &= raw[(f * 2 + 0) * 4 + k] == want0 && raw[(f * 2 + 1) * 4 + k] == want1;
        }
    check(ordered, "native DSD: words carry each channel's DSD bytes in order (MSB-first)");

    // Through the player and its ring (float slots, only copied): bytes intact.
    FakeOutput* fake = nullptr;
    bool refuseNative = false, refuseExact = false;
    struct NativeFake : FakeOutput {
        bool* noNative; bool* noExact;
        bool open(const StreamFormat& f, PullFn p, std::string& e) override {
            if ((f.nativeDsd && *noNative) || (f.bitExact && *noExact)) { e = "refused"; return false; }
            format = f; pull = std::move(p); return true;
        }
    };
    Player player([&] { auto o = std::make_unique<NativeFake>(); o->noNative = &refuseNative; o->noExact = &refuseExact; fake = o.get(); return o; });
    player.setDsdModes(/*dop=*/true, /*native=*/true);
    check(player.open(path, err) && fake->format.nativeDsd && fake->format.dsdRate == 2822400 && fake->format.bitExact,
          "native DSD: player opens native first when allowed");
    player.play();
    std::vector<float> got;
    std::vector<float> buf(256 * 2);
    for (int tries = 0; got.size() < 2048 * 2 && tries < 4000; tries++) {
        const int k = fake->pull(buf.data(), 256);
        got.insert(got.end(), buf.begin(), buf.begin() + k * 2);
        if (k == 0) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    check(got.size() == 2048 * 2 && !memcmp(got.data(), words.data(), got.size() * sizeof(float)), "native DSD: bytes arrive intact through the player's ring");
    refuseNative = true;
    check(player.open(path, err) && fake->format.bitExact && !fake->format.nativeDsd && player.info().dop, "native DSD: refused -> DoP");
    refuseExact = true;
    check(player.open(path, err) && !fake->format.bitExact && !player.info().dop && !player.info().nativeDsd, "native DSD: DoP refused too -> PCM");
    player.close();

    // The feeder: any byte count per call, leftovers kept, LSB-first reversal, silence on underrun.
    std::vector<float> src(words);
    size_t pos = 0;
    PullFn pull = [&](float* out, int frames) {
        const int k = (int) std::min<size_t>((size_t) frames, src.size() / 2 - pos);
        memcpy(out, src.data() + pos * 2, (size_t) k * 2 * sizeof(float));
        pos += k;
        return k;
    };
    DsdFeeder feeder;
    feeder.init(2);
    std::vector<uint8_t> ch0, ch1;
    bool sizesOk = true;
    for (int bytes : {3, 7, 1, 512, 13, 1000, 4096}) {
        std::vector<uint8_t> a(bytes), b(bytes);
        uint8_t* outs[2] = {a.data(), b.data()};
        sizesOk &= feeder.fill(pull, outs, bytes, false) == bytes;
        ch0.insert(ch0.end(), a.begin(), a.end());
        ch1.insert(ch1.end(), b.begin(), b.end());
    }
    bool streams = sizesOk;
    for (size_t i = 0; i < ch0.size() && streams; i++) streams &= ch0[i] == raw[(i / 4 * 2 + 0) * 4 + i % 4] && ch1[i] == raw[(i / 4 * 2 + 1) * 4 + i % 4];
    check(streams, "DSD feeder: odd buffer sizes give one continuous byte stream per channel");
    std::vector<uint8_t> a(64), b(64);
    uint8_t* outs[2] = {a.data(), b.data()};
    pos = 0;
    feeder.init(2);
    feeder.fill(pull, outs, 64, /*lsbFirst=*/true);
    check(a[0] == reverseBits(raw[0]) && b[5] == reverseBits(raw[(1 * 2 + 1) * 4 + 1]), "DSD feeder: LSB-first drivers get each byte bit-reversed");
    pos = src.size() / 2;  // nothing left
    const int real = feeder.fill(pull, outs, 64, false);
    check(real == 0 && a[0] == kDsdSilence && b[63] == kDsdSilence, "DSD feeder: underrun fills DSD silence (0x69), not zeros");
}

static void testPlanar() {
    // ASIO buffers: one per channel. Stereo 24-bit stays exact; mono goes to both outputs.
    const float in[6] = {1.0f / 8388608, -2.0f / 8388608, 3.0f / 8388608, -4.0f / 8388608, 0.5f, -0.5f};
    uint8_t l[9], r[9];
    uint8_t* outs[2] = {l, r};
    std::vector<float> scratch;
    convertToPlanar(in, 2, 3, outs, 2, SampleFormat::Int24Packed, scratch);
    auto s24 = [](const uint8_t* p) { return (int32_t) ((uint32_t) p[0] << 8 | (uint32_t) p[1] << 16 | (uint32_t) p[2] << 24) >> 8; };
    check(s24(l) == 1 && s24(r) == -2 && s24(l + 3) == 3 && s24(r + 3) == -4 && s24(l + 6) == 4194304 && s24(r + 6) == -4194304,
          "planar: stereo split per channel, 24-bit exact");
    int16_t a[3], b[3];
    uint8_t* outs16[2] = {reinterpret_cast<uint8_t*>(a), reinterpret_cast<uint8_t*>(b)};
    const float mono[3] = {0.25f, -0.25f, 1.0f / 32768};
    convertToPlanar(mono, 1, 3, outs16, 2, SampleFormat::Int16, scratch);
    check(a[0] == 8192 && b[0] == 8192 && a[1] == -8192 && b[2] == 1, "planar: mono plays on both outputs");
}

// ---- DSP ------------------------------------------------------------------------------------------
static void testDsp() {
    // Air hints: the Android table.
    SourceInfo i;
    i.channels = 2;
    check(!airHintFor(i).lossy, "dsp: lossless files give no lossy hint");
    i.lossy = true; i.codec = "MP3"; i.bitrate = 128000;
    check(airHintFor(i).lossy && airHintFor(i).priorCutoffHz == 16000, "dsp: 128 kbps MP3 guesses 16 kHz");
    i.bitrate = 320000;
    check(airHintFor(i).priorCutoffHz == 0, "dsp: 320 kbps counts as full range");
    i.bitrate = 64000; i.channels = 1;
    check(airHintFor(i).priorCutoffHz == 16000, "dsp: mono counts double (64 kbps mono guesses like 128 stereo)", airHintFor(i).priorCutoffHz);
    i.codec = "Opus"; i.channels = 2;
    check(airHintFor(i).lossy && airHintFor(i).priorCutoffHz == 0, "dsp: Opus is full band");
    std::string err;
    auto mp3 = openSource(asset("clip.mp3"), err);
    check(mp3 && mp3->info().lossy && mp3->info().bitrate > 0, "dsp: MP3 reports lossy with its bitrate", mp3 ? mp3->info().bitrate : -1);

    FakeOutput* fake = nullptr;
    Player player([&] { auto o = std::make_unique<FakeOutput>(); fake = o.get(); return o; });
    auto ref = openSource(asset("clip16.flac"), err);
    const auto all = decodeAll(*ref);
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

    // Master on with only Volume -6.02 dB: exactly half (the first settings snap, no ramp).
    DspSettings half;
    half.enabled = true;
    half.params.volumeDb = -6.0206f;
    player.setDsp(half);
    check(player.open(asset("clip16.flac"), err) && fake->format.bits == 0, "dsp: on = no source bit depth to keep (no bit-perfect claim)");
    player.play();
    auto out = pullFrames(4000);
    double worst = 0;
    for (size_t k = 0; k < out.size(); k++) worst = std::max(worst, (double) std::fabs(out[k] - all[k] * 0.5f));
    check(out.size() == 8000 && worst < 1e-5, "dsp: volume -6 dB halves the samples", worst);
    check(player.outputDescription().find("DSP on") != std::string::npos, "dsp: the output line says DSP on");
    dsp::Meters m{};
    check(player.dspMeters(m) && m.outputPeak > 0, "dsp: meters while processing", m.outputPeak);

    // Master off: untouched again, at once (same stream).
    half.enabled = false;
    player.setDsp(half);
    pullFrames(1);  // one buffer may still be processed while the change lands
    const int64_t at = player.positionFrames();
    auto plain = pullFrames(2000);
    check(plain.size() == 4000 && !memcmp(plain.data(), all.data() + at * 2, plain.size() * sizeof(float)), "dsp: off = bit-exact samples");
    check(!player.dspMeters(m) && player.outputDescription().find("DSP") == std::string::npos, "dsp: off = no meters, no DSP on the output line");

    // Seeking with it on: fresh state, the right samples (volume only, so exact).
    half.enabled = true;
    player.setDsp(half);
    player.seek(30000);
    auto sought = pullFrames(1000);
    worst = 0;
    for (size_t k = 0; k < sought.size(); k++) worst = std::max(worst, (double) std::fabs(sought[k] - all[30000 * 2 + k] * 0.5f));
    check(sought.size() == 2000 && worst < 1e-5, "dsp: after a seek it processes the new position", worst);

    // DSD with the DSP on opens as PCM, even where native / DoP would be allowed.
    player.setDsdModes(true, true);
    check(player.open(writeDsf(0.2), err) && !fake->format.bitExact && !player.info().dop && !player.info().nativeDsd && player.outputDescription().find("DSP on") != std::string::npos,
          "dsp: DSD plays as PCM while the DSP is on " + err);
    half.enabled = false;
    player.setDsp(half);
    check(player.open(writeDsf(0.2), err) && fake->format.bitExact, "dsp: off again = DSD bit-exact again");

    // The full chain on music runs and stays finite while settings change under it (TSan: no races).
    DspSettings full;
    full.enabled = true;
    full.params.companderOn = full.params.bassOn = full.params.impactOn = full.params.vocalOn = true;
    player.setDsp(full);
    check(player.open(asset("clip24.flac"), err), "dsp: full chain opens " + err);
    player.play();
    std::atomic<bool> stop{false};
    std::thread tweak([&] {
        DspSettings t = full;
        for (int k = 0; !stop; k++) { t.params.airPercent = (float) (k % 100); player.setDsp(t); std::this_thread::sleep_for(std::chrono::microseconds(200)); }
    });
    auto busy = pullFrames(20000);
    stop = true;
    tweak.join();
    bool finite = busy.size() == 40000;
    for (float v : busy) finite &= std::isfinite(v) && std::fabs(v) < 4.0f;
    check(finite, "dsp: full chain on music stays finite while settings change");
    player.close();
    player.setDsp(DspSettings{});
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
    testConversion();
    testDop();
    testNativeDsd();
    testPlanar();
    testDsp();
    std::printf(failures ? "%d desktop core test(s) FAILED\n" : "desktop core tests passed\n", failures);
    return failures ? 1 : 0;
}
