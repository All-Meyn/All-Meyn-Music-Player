// JNI for the DSP chain: com.allmeyn.player.dsp.NativeDsp.
// Handles are pointers (may be negative on arm64 with tagged pointers): only 0 means "none".
#include <jni.h>

#include <new>

#include "dsp_chain.h"

#include <algorithm>
#include <vector>

using allmeyn::dsp::Chain;
using allmeyn::dsp::kMeterCount;
using allmeyn::dsp::kNumProcessors;
using allmeyn::dsp::EqBand;
using allmeyn::dsp::kMaxEqBands;
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
 *          air%, eqOn, eqPreampDb, eqBandCount, (eqType, Hz, dB, Q) x eqBandCount];
 * order: kNumProcessors processor ids.
 */
JNIEXPORT void JNICALL Java_com_allmeyn_player_dsp_NativeDsp_setParams(JNIEnv* env, jclass, jlong h, jfloatArray values, jintArray order) {
    constexpr int kValues = 16, kEqHead = 3, kMaxValues = kValues + kEqHead + 4 * kMaxEqBands + 1;
    const jsize length = h == 0 ? 0 : env->GetArrayLength(values);
    if (h == 0 || length < kValues || env->GetArrayLength(order) < kNumProcessors) return;
    jfloat v[kMaxValues] = {};
    jint o[kNumProcessors];
    env->GetFloatArrayRegion(values, 0, std::min<jsize>(length, kMaxValues), v);
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
    // Headphone EQ: [on, preampDb, bandCount, (type, Hz, dB, Q) x bandCount]
    if (length >= kValues + kEqHead) {
        p.eqOn = v[16] != 0;
        p.eqPreampDb = v[17];
        const int n = std::clamp((int) v[18], 0, std::min(kMaxEqBands, (int) (std::min<jsize>(length, kMaxValues) - kValues - kEqHead) / 4));
        p.eqBandCount = n;
        for (int i = 0; i < n; i++) {
            const jfloat* b = v + kValues + kEqHead + 4 * i;
            p.eqBands[i] = EqBand{(int) b[0], b[1], b[2], b[3]};
        }
        // Then the convolution EQ's on/off.
        const int convAt = kValues + kEqHead + 4 * n;
        p.convOn = std::min<jsize>(length, kMaxValues) > convAt && v[convAt] != 0;
    }
    for (int i = 0; i < kNumProcessors; i++) p.order[i] = o[i];
    chainOf(h)->setParams(p);
}

/** The current song's source: lossy (with a bitrate-based cutoff guess in Hz, 0 = none) or not. */
static std::vector<float> floats(JNIEnv* env, jfloatArray a) {
    std::vector<float> v;
    if (a) {
        v.resize((size_t) env->GetArrayLength(a));
        if (!v.empty()) env->GetFloatArrayRegion(a, 0, (jsize) v.size(), v.data());
    }
    return v;
}

/**
 * The convolution EQ's filter, built for this chain's sample rate and handed over (null arrays =
 * none): an impulse response ([irLeft], optional [irRight], at [irRate]) or GraphicEQ points
 * ([graphicHz], [graphicDb]), plus [gainDb]. Builds right here (a few ms): call outside the audio
 * callback; the swap itself happens in the next process().
 */
JNIEXPORT void JNICALL Java_com_allmeyn_player_dsp_NativeDsp_setConvolution(JNIEnv* env, jclass, jlong h, jfloatArray irLeft, jfloatArray irRight,
                                                                           jfloat irRate, jfloatArray graphicHz, jfloatArray graphicDb, jfloat gainDb) {
    if (h == 0) return;
    Chain* c = chainOf(h);
    allmeyn::dsp::ConvSource s;
    s.ir[0] = floats(env, irLeft);
    s.ir[1] = floats(env, irRight);
    s.irChannels = s.ir[0].empty() ? 0 : (s.ir[1].empty() ? 1 : 2);
    s.irRate = irRate;
    s.graphicHz = floats(env, graphicHz);
    s.graphicDb = floats(env, graphicDb);
    s.gainDb = gainDb;
    c->offerConvolution(allmeyn::dsp::buildConvKernel(s, c->sampleRate()));
}

JNIEXPORT void JNICALL Java_com_allmeyn_player_dsp_NativeDsp_setSource(JNIEnv*, jclass, jlong h, jboolean lossy, jfloat priorCutoffHz) {
    if (h != 0) chainOf(h)->setSource(lossy == JNI_TRUE, priorCutoffHz);
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
