// JNI for the desktop engine: com.allmeyn.desktop.engine.NativeEngine.
// Stage W1: reports what the engine was built with (FFmpeg version and decoders) and lists the
// Windows audio output devices (WASAPI), so the build chain and device access can be checked.
#include <jni.h>

#include <string>
#include <vector>

#include "audio_devices.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
}

namespace {

jstring toJava(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

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

}  // extern "C"
