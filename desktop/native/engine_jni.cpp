// JNI for the desktop engine: com.allmeyn.desktop.engine.NativeEngine.
// What the engine was built with, the Windows outputs, and the player (one song at a time).
#include <jni.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "audio_devices.h"
#include "core/player.h"
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
allmeyn::Player& player() {
    static allmeyn::Player p([] { return std::make_unique<allmeyn::WasapiOutput>(); });
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

/** Opens a song (paused at the start). Returns null, or why it can't be played. */
JNIEXPORT jstring JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerOpen(JNIEnv* env, jclass, jstring path) {
    std::lock_guard<std::mutex> g(g_lock);
    std::string error;
    if (player().open(fromJava(env, path), error)) return nullptr;
    return toJava(env, error.empty() ? std::string("Can't play this file") : error);
}

/** [codec, sampleRate, channels, bits, totalFrames, title, artist, album, output]. */
JNIEXPORT jobjectArray JNICALL Java_com_allmeyn_desktop_engine_NativeEngine_playerInfo(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> g(g_lock);
    const auto i = player().info();
    return toJava(env, std::vector<std::string>{
        i.codec, std::to_string(i.sampleRate), std::to_string(i.channels), std::to_string(i.bitsPerSample),
        std::to_string(i.totalFrames), i.title, i.artist, i.album, player().outputDescription()});
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

}  // extern "C"
