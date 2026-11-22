// JNI for the DSP chain: com.allmeyn.player.dsp.NativeDsp.
// Handles are pointers (may be negative on arm64 with tagged pointers): only 0 means "none".
#include <jni.h>

#include <new>

#include "dsp_chain.h"

using allmeyn::dsp::Chain;
using allmeyn::dsp::kMeterCount;
using allmeyn::dsp::kNumProcessors;
using allmeyn::dsp::Params;

namespace {
Chain* chainOf(jlong h) { return reinterpret_cast<Chain*>(h); }
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_allmeyn_player_dsp_NativeDsp_create(JNIEnv*, jclass, jint sampleRate, jint channels) {
    if (sampleRate <= 0 || channels < 1 || channels > 2) return 0;
    return reinterpret_cast<jlong>(new (std::nothrow) Chain(sampleRate, channels));
}

JNIEXPORT void JNICALL Java_com_allmeyn_player_dsp_NativeDsp_destroy(JNIEnv*, jclass, jlong h) {
    delete chainOf(h);
}

JNIEXPORT void JNICALL Java_com_allmeyn_player_dsp_NativeDsp_reset(JNIEnv*, jclass, jlong h) {
    if (h != 0) chainOf(h)->reset();
}

/**
 * values: [companderOn, expansion%, transitionDb, hfTransitionDb, bassOn, bass%, bassTransitionDb,
 *          impactOn, impact%, impactReleaseMs, volumeDb, softClip, vocalOn, presence%, detail%,
 *          air%]; order: kNumProcessors processor ids.
 */
JNIEXPORT void JNICALL Java_com_allmeyn_player_dsp_NativeDsp_setParams(JNIEnv* env, jclass, jlong h, jfloatArray values, jintArray order) {
    constexpr int kValues = 16;
    if (h == 0 || env->GetArrayLength(values) < kValues || env->GetArrayLength(order) < kNumProcessors) return;
    jfloat v[kValues];
    jint o[kNumProcessors];
    env->GetFloatArrayRegion(values, 0, kValues, v);
    env->GetIntArrayRegion(order, 0, kNumProcessors, o);
    Params p;
    p.companderOn = v[0] != 0;
    p.expansionPercent = v[1];
    p.transitionDb = v[2];
    p.hfTransitionDb = v[3];
    p.bassOn = v[4] != 0;
    p.bassPercent = v[5];
    p.bassTransitionDb = v[6];
    p.impactOn = v[7] != 0;
    p.impactPercent = v[8];
    p.impactReleaseMs = v[9];
    p.volumeDb = v[10];
    p.softClip = v[11] != 0;
    p.vocalOn = v[12] != 0;
    p.presencePercent = v[13];
    p.detailPercent = v[14];
    p.airPercent = v[15];
    for (int i = 0; i < kNumProcessors; i++) p.order[i] = o[i];
    chainOf(h)->setParams(p);
}

/**
 * Processes [frames] interleaved float frames at byte [offset] of the direct buffer, in place, and
 * copies the meters into [meters]. False if the buffer isn't direct (the caller copies instead).
 */
JNIEXPORT jboolean JNICALL Java_com_allmeyn_player_dsp_NativeDsp_process(JNIEnv* env, jclass, jlong h, jobject buffer, jint offset, jint frames, jfloatArray meters) {
    if (h == 0) return JNI_FALSE;
    auto* base = static_cast<unsigned char*>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr) return JNI_FALSE;
    Chain* c = chainOf(h);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    const jlong bytes = (jlong) frames * c->channels() * (jlong) sizeof(float);
    if (offset < 0 || frames < 0 || offset + bytes > capacity || (offset % sizeof(float)) != 0) return JNI_FALSE;
    c->process(reinterpret_cast<float*>(base + offset), frames);
    if (meters != nullptr && env->GetArrayLength(meters) >= kMeterCount) {
        static_assert(sizeof(allmeyn::dsp::Meters) == kMeterCount * sizeof(float), "meters are plain floats");
        env->SetFloatArrayRegion(meters, 0, kMeterCount, reinterpret_cast<const jfloat*>(&c->meters()));
    }
    return JNI_TRUE;
}

}  // extern "C"
