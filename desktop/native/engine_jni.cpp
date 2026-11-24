// JNI for the desktop engine: com.allmeyn.desktop.engine.NativeEngine.
// What the engine was built with, the Windows outputs, and the player (one song at a time).
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "asio_output.h"
#include "audio_devices.h"
#include "endpoint_volume.h"
#include "core/player.h"
#include "core/tags.h"
#include "folder_picker.h"
#include "wasapi_output.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
}

namespace {

jstring toJava(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

std::string fromJava(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// One player for the app; JNI calls may come from different JVM threads, so they're serialised.
std::mutex g_lock;
// The output songs are opened on (Settings): device and mode, or an ASIO driver ("asio:<name>").
std::mutex g_outputLock;
allmeyn::WasapiConfig g_output;
const std::string kAsioPrefix = "asio:";
bool isAsio(const std::string& id) { return id.compare(0, kAsioPrefix.size(), kAsioPrefix) == 0; }
allmeyn::Player& player() {
    static allmeyn::Player p([]() -> std::unique_ptr<allmeyn::Output> {
        std::lock_guard<std::mutex> g(g_outputLock);
        if (isAsio(g_output.deviceId)) return std::make_unique<allmeyn::AsioOutput>(g_output.deviceId.substr(kAsioPrefix.size()));
        return std::make_unique<allmeyn::WasapiOutput>(g_output);
    });
    return p;
}

jobjectArray toJava(JNIEnv* env, const std::vector<std::string>& items) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray((jsize) items.size(), stringClass, nullptr);
    for (size_t i = 0; i < items.size(); i++) {
        jstring s = toJava(env, items[i]);
        env->SetObjectArrayElement(out, (jsize) i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

}  // namespace

namespace allmeyn { void setDarkTitleBar(void* hwnd, bool dark); }  // window_theme.cpp
namespace allmeyn { int startMediaKeys(); void stopMediaKeys(); int pollMediaKey(); }  // media_keys.cpp

extern "C" {

/** "Lavc61.19.101" style libavcodec ident (same as the Android decoder reports). */
JNIEXPORT jstring JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_ffmpegVersion(JNIEnv* env, jclass) {
    return toJava(env, LIBAVCODEC_IDENT);
}

/** Names of the audio decoders and demuxers built into FFmpeg, e.g. ["decoder:flac", "demuxer:mov,mp4,m4a,..."]. */
JNIEXPORT jobjectArray JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_ffmpegComponents(JNIEnv* env, jclass) {
    std::vector<std::string> names;
    void* it = nullptr;
    while (const AVCodec* c = av_codec_iterate(&it)) {
        if (c->type == AVMEDIA_TYPE_AUDIO && av_codec_is_decoder(c)) names.push_back(std::string("decoder:") + c->name);
    }
    it = nullptr;
    while (const AVInputFormat* f = av_demuxer_iterate(&it)) names.push_back(std::string("demuxer:") + f->name);
    names.push_back("decoder:allmeyn_dsd");  // our DSF/DFF decoder (the Android app's dsd.c)
    return toJava(env, names);
}

/**
 * Active output devices, one string each: "id\tname\tdefault(0/1)\trate\tbits\tchannels", where
 * rate/bits/channels are the device's shared-mode mix format (0 when it couldn't be read).
 */
JNIEXPORT jobjectArray JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_outputDevices(JNIEnv* env, jclass) {
    std::vector<std::string> rows;
    for (const auto& d : allmeyn::listOutputDevices()) {
        rows.push_back(d.id + "\t" + d.name + "\t" + (d.isDefault ? "1" : "0") + "\t" + std::to_string(d.mixRate) +
                       "\t" + std::to_string(d.mixBits) + "\t" + std::to_string(d.mixChannels));
    }
    return toJava(env, rows);
}

// ---- player ---------------------------------------------------------------------------------

/** Installed ASIO drivers, by name (open one with playerSetOutput("asio:" + name, ...)). */
JNIEXPORT jobjectArray JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_asioDrivers(JNIEnv* env, jclass) {
    return toJava(env, allmeyn::listAsioDrivers());
}

/** Opens the ASIO driver's control panel; null if it opened, else why not. */
JNIEXPORT jstring JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_asioControlPanel(JNIEnv* env, jclass, jstring driver) {
    std::string error;
    if (allmeyn::openAsioControlPanel(fromJava(env, driver), error)) return nullptr;
    return env->NewStringUTF(error.c_str());
}

/**
 * The output's volume ("" = Windows' default output): [level 0..1, dB, min dB, max dB, step dB,
 * muted 0/1, hardware 0/1], or null if the device has none.
 */
JNIEXPORT jfloatArray JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_dacVolume(JNIEnv* env, jclass, jstring deviceId) {
    const auto v = allmeyn::readEndpointVolume(fromJava(env, deviceId));
    if (!v.ok) return nullptr;
    const jfloat f[7] = {v.level, v.db, v.minDb, v.maxDb, v.stepDb, v.muted ? 1.0f : 0.0f, v.hardware ? 1.0f : 0.0f};
    jfloatArray out = env->NewFloatArray(7);
    if (out) env->SetFloatArrayRegion(out, 0, 7, f);
    return out;
}

JNIEXPORT jboolean JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_setDacVolume(JNIEnv* env, jclass, jstring deviceId, jfloat level) {
    return allmeyn::setEndpointVolume(fromJava(env, deviceId), level) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_setDacMute(JNIEnv* env, jclass, jstring deviceId, jboolean muted) {
    return allmeyn::setEndpointMute(fromJava(env, deviceId), muted == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

/**
 * The output for songs opened from now on: [deviceId] (empty = Windows' default; "asio:<name>" =
 * that ASIO driver), exclusive (bit-perfect) or shared for WASAPI, and DSD as DoP when the output
 * can take it bit-exact. ASIO also tries native DSD first (falling back to DoP, then PCM).
 */
JNIEXPORT void JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerSetOutput(JNIEnv* env, jclass, jstring deviceId, jboolean exclusive, jboolean dop) {
    bool asio;
    {
        std::lock_guard<std::mutex> g(g_outputLock);
        g_output.deviceId = fromJava(env, deviceId);
        g_output.exclusive = exclusive == JNI_TRUE;
        asio = isAsio(g_output.deviceId);
    }
    player().setDsdModes(dop == JNI_TRUE && (asio || exclusive == JNI_TRUE), /*native=*/asio);
}

/**
 * The DSP chain (the Android app's), applied at once. [values] as the Android NativeDsp.setParams:
 * [companderOn, expansion%, transitionDb, hfTransitionDb, bassOn, bass%, bassTransitionDb, impactOn,
 *  impact%, impactReleaseMs, volumeDb, softClip, vocalOn, presence%, detail%, air%]; [order]: the
 * processor ids (Vocal is always put first). With the DSP processing, DSD songs open as PCM.
 */
JNIEXPORT void JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerSetDsp(JNIEnv* env, jclass, jboolean enabled, jfloatArray values, jintArray order) {
    using allmeyn::dsp::kMaxEqBands;
    constexpr int kValues = 16, kEqHead = 3, kMaxValues = kValues + kEqHead + 4 * kMaxEqBands + 1;
    if (!values || !order || env->GetArrayLength(values) < kValues || env->GetArrayLength(order) < allmeyn::dsp::kNumProcessors) return;
    const jsize length = std::min<jsize>(env->GetArrayLength(values), kMaxValues);
    jfloat v[kMaxValues] = {};
    jint o[allmeyn::dsp::kNumProcessors];
    env->GetFloatArrayRegion(values, 0, length, v);
    env->GetIntArrayRegion(order, 0, allmeyn::dsp::kNumProcessors, o);
    allmeyn::DspSettings s;
    s.enabled = enabled == JNI_TRUE;
    auto& p = s.params;
    p.companderOn = v[0] != 0; p.expansionPercent = v[1]; p.transitionDb = v[2]; p.hfTransitionDb = v[3];
    p.bassOn = v[4] != 0; p.bassPercent = v[5]; p.bassTransitionDb = v[6];
    p.impactOn = v[7] != 0; p.impactPercent = v[8]; p.impactReleaseMs = v[9];
    p.volumeDb = v[10]; p.softClip = v[11] != 0;
    p.vocalOn = v[12] != 0; p.presencePercent = v[13]; p.detailPercent = v[14]; p.airPercent = v[15];
    // Headphone EQ: [on, preampDb, bandCount, (type, Hz, dB, Q) x bandCount] (as on Android)
    if (length >= kValues + kEqHead) {
        p.eqOn = v[16] != 0;
        p.eqPreampDb = v[17];
        const int n = std::clamp((int) v[18], 0, std::min(kMaxEqBands, (int) (length - kValues - kEqHead) / 4));
        p.eqBandCount = n;
        for (int i = 0; i < n; i++) {
            const jfloat* b = v + kValues + kEqHead + 4 * i;
            p.eqBands[i] = allmeyn::dsp::EqBand{(int) b[0], b[1], b[2], b[3]};
        }
        const int convAt = kValues + kEqHead + 4 * n;  // then the convolution EQ's on/off
        p.convOn = length > convAt && v[convAt] != 0;
    }
    for (int i = 0; i < allmeyn::dsp::kNumProcessors; i++) p.order[i] = o[i];
    player().setDsp(s);
}

/**
 * The convolution EQ's filter (null arrays = none): an impulse response ([irLeft], optional
 * [irRight], at [irRate]) or GraphicEQ points, plus [gainDb]. Built here for the playing stream
 * (a few ms; called off the UI thread) and kept for later ones.
 */
JNIEXPORT void JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerSetConvolution(JNIEnv* env, jclass, jfloatArray irLeft, jfloatArray irRight,
                                                                                       jfloat irRate, jfloatArray graphicHz, jfloatArray graphicDb, jfloat gainDb) {
    auto floats = [&](jfloatArray a) {
        std::vector<float> v;
        if (a) { v.resize((size_t) env->GetArrayLength(a)); if (!v.empty()) env->GetFloatArrayRegion(a, 0, (jsize) v.size(), v.data()); }
        return v;
    };
    auto s = std::make_shared<allmeyn::dsp::ConvSource>();
    s->ir[0] = floats(irLeft);
    s->ir[1] = floats(irRight);
    s->irChannels = s->ir[0].empty() ? 0 : (s->ir[1].empty() ? 1 : 2);
    s->irRate = irRate;
    s->graphicHz = floats(graphicHz);
    s->graphicDb = floats(graphicDb);
    s->gainDb = gainDb;
    const bool empty = s->irChannels == 0 && s->graphicHz.size() < 2;
    player().setConvolution(empty ? nullptr : std::move(s));
}

/** The DSP meters (Android NativeDsp layout, 12 floats) while the chain processes; else null. */
JNIEXPORT jfloatArray JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerDspMeters(JNIEnv* env, jclass) {
    allmeyn::dsp::Meters m{};
    if (!player().dspMeters(m)) return nullptr;
    static_assert(sizeof(allmeyn::dsp::Meters) == allmeyn::dsp::kMeterCount * sizeof(float), "meters are plain floats");
    jfloatArray out = env->NewFloatArray(allmeyn::dsp::kMeterCount);
    if (out) env->SetFloatArrayRegion(out, 0, allmeyn::dsp::kMeterCount, reinterpret_cast<const jfloat*>(&m));
    return out;
}

/** Opens a song (paused at the start). Returns null, or why it can't be played. */
JNIEXPORT jstring JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerOpen(JNIEnv* env, jclass, jstring path) {
    std::lock_guard<std::mutex> g(g_lock);
    std::string error;
    if (player().open(fromJava(env, path), error)) return nullptr;
    return toJava(env, error.empty() ? std::string("Can't play this file") : error);
}

/** Queues the song after the current one (gapless if same rate/channels). Null, or why it can't play. */
JNIEXPORT jstring JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerSetNext(JNIEnv* env, jclass, jstring path) {
    // Opening the file happens outside the lock: the player guards its next song itself.
    std::string error;
    if (player().setNext(fromJava(env, path), error)) return nullptr;
    return toJava(env, error.empty() ? std::string("Can't play this file") : error);
}

JNIEXPORT void JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerClearNext(JNIEnv*, jclass) {
    player().clearNext();
}

/** Counts gapless moves to the next song. */
JNIEXPORT jint JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerTrackSerial(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> g(g_lock);
    return player().trackSerial();
}

/** [codec, sampleRate, channels, bits, totalFrames, title, artist, album, output, path] of the song heard. */
JNIEXPORT jobjectArray JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerInfo(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> g(g_lock);
    const auto i = player().info();
    char gain[16] = "";
    if (!std::isnan(i.replayGainDb)) snprintf(gain, sizeof gain, "%.2f", i.replayGainDb);
    return toJava(env, std::vector<std::string>{
        i.codec, std::to_string(i.sampleRate), std::to_string(i.channels), std::to_string(i.bitsPerSample),
        std::to_string(i.totalFrames), i.title, i.artist, i.album, player().outputDescription(), i.path, gain});
}

/** ReplayGain for songs opened from now on: 0 off, 1 track, 2 album (the app sends 0 for bit-perfect outputs). */
JNIEXPORT void JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerSetReplayGain(JNIEnv*, jclass, jint mode) {
    player().setReplayGain(mode == 1 ? allmeyn::ReplayGainMode::Track : mode == 2 ? allmeyn::ReplayGainMode::Album : allmeyn::ReplayGainMode::Off);
}

JNIEXPORT void JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerPlay(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> g(g_lock);
    player().play();
}

JNIEXPORT void JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerPause(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> g(g_lock);
    player().pause();
}

JNIEXPORT void JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerSeek(JNIEnv*, jclass, jlong frame) {
    std::lock_guard<std::mutex> g(g_lock);
    player().seek(frame);
}

JNIEXPORT void JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerClose(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> g(g_lock);
    player().close();
}

/** Frame being heard. */
JNIEXPORT jlong JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerPosition(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> g(g_lock);
    return player().positionFrames();
}

/** 0 idle, 1 playing, 2 paused, 3 ended, 4 error. */
JNIEXPORT jint JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerState(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> g(g_lock);
    return (jint) player().state();
}

JNIEXPORT jstring JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> g(g_lock);
    return toJava(env, player().error());
}

// ---- library ---------------------------------------------------------------------------------

/**
 * [ok(1/0), title, artist, album, albumArtist, track, disc, year, durationMs, format, sampleRate,
 * bits, hasCover(1/0), error]. Safe to call from several threads (no shared state).
 */
JNIEXPORT jobjectArray JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_readTags(JNIEnv* env, jclass, jstring path) {
    const auto t = allmeyn::readTags(fromJava(env, path));
    return toJava(env, std::vector<std::string>{
        t.ok ? "1" : "0", t.title, t.artist, t.album, t.albumArtist, std::to_string(t.track), std::to_string(t.disc),
        std::to_string(t.year), std::to_string(t.durationMs), t.format, std::to_string(t.sampleRate), std::to_string(t.bits),
        t.hasCover ? "1" : "0", t.error});
}

/** The embedded cover's bytes, or null. */
JNIEXPORT jbyteArray JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_readCover(JNIEnv* env, jclass, jstring path) {
    const auto bytes = allmeyn::readCover(fromJava(env, path));
    if (bytes.empty()) return nullptr;
    jbyteArray out = env->NewByteArray((jsize) bytes.size());
    env->SetByteArrayRegion(out, 0, (jsize) bytes.size(), reinterpret_cast<const jbyte*>(bytes.data()));
    return out;
}

/** The Windows folder picker over [owner] (HWND). The folder, or null if cancelled. */
/** Media keys: start (returns the keys taken as bits), stop, and the next press (0 = none). */
JNIEXPORT jint JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_startMediaKeys(JNIEnv*, jclass) { return allmeyn::startMediaKeys(); }
JNIEXPORT void JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_stopMediaKeys(JNIEnv*, jclass) { allmeyn::stopMediaKeys(); }
JNIEXPORT jint JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_pollMediaKey(JNIEnv*, jclass) { return allmeyn::pollMediaKey(); }

JNIEXPORT void JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_setDarkTitleBar(JNIEnv*, jclass, jlong hwnd, jboolean dark) {
    allmeyn::setDarkTitleBar(reinterpret_cast<void*>(static_cast<intptr_t>(hwnd)), dark == JNI_TRUE);
}

JNIEXPORT jstring JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_pickFolder(JNIEnv* env, jclass, jlong owner, jstring title) {
    const std::string path = allmeyn::pickFolder(reinterpret_cast<void*>(owner), fromJava(env, title));
    return path.empty() ? nullptr : toJava(env, path);
}

}  // extern "C"
