// JNI bridge for com.allmeyn.player.decode.NativeDecoder.

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "decoder.h"

#define JNI_FN(name) Java_com_allmeyn_player_decode_NativeDecoder_##name

typedef struct {
    Decoder *d;
    uint8_t *scratch;
    int scratch_cap;
} Handle;

static jstring g_last_error_str(JNIEnv *env, const char *s) { return (*env)->NewStringUTF(env, s); }
static char g_last_error[160];

/** Returns a handle, 0 if the file isn't a format we decode, or -1 on error (see lastError). */
JNIEXPORT jlong JNICALL JNI_FN(open)(JNIEnv *env, jclass clazz, jint fd, jboolean dop, jint native_max_rate) {
    (void) env; (void) clazz;
    char err[160] = "";
    Decoder *d = dsd_open(fd, dop, native_max_rate > 0 ? (uint32_t) native_max_rate : 0, err, sizeof err);
    if (!d && !err[0]) d = ff_open(fd, err, sizeof err);
    if (!d) {
        strncpy(g_last_error, err, sizeof g_last_error - 1);
        return err[0] ? -1 : 0;
    }
    Handle *h = calloc(1, sizeof(Handle));
    if (!h) {
        d->close(d);
        return -1;
    }
    h->d = d;
    return (jlong) (intptr_t) h;
}

JNIEXPORT jstring JNICALL JNI_FN(lastError)(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return g_last_error_str(env, g_last_error);
}

/** [sampleRate, channels, format, totalFrames, isDsd, isDop, isNative] */
JNIEXPORT jlongArray JNICALL JNI_FN(info)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    DecInfo *in = &((Handle *) (intptr_t) handle)->d->info;
    jlong v[7] = {in->sample_rate, in->channels, in->format, in->total_frames, in->is_dsd, in->is_dop, in->is_native};
    jlongArray a = (*env)->NewLongArray(env, 7);
    (*env)->SetLongArrayRegion(env, a, 0, 7, v);
    return a;
}

JNIEXPORT jstring JNICALL JNI_FN(describe)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    return (*env)->NewStringUTF(env, ((Handle *) (intptr_t) handle)->d->info.desc);
}

JNIEXPORT jint JNICALL JNI_FN(seek)(JNIEnv *env, jclass clazz, jlong handle, jlong frame) {
    (void) env; (void) clazz;
    Decoder *d = ((Handle *) (intptr_t) handle)->d;
    return d->seek(d, frame);
}

/** Decodes whole frames into buf[off, off+len). Returns bytes written, 0 at end, -1 on error. */
JNIEXPORT jint JNICALL JNI_FN(read)(JNIEnv *env, jclass clazz, jlong handle, jbyteArray buf, jint off, jint len) {
    (void) clazz;
    Handle *h = (Handle *) (intptr_t) handle;
    int fb = h->d->info.channels * dec_sample_bytes(h->d->info.format);
    int frames = len / fb;
    if (frames <= 0) return 0;
    int need = frames * fb;
    if (need > h->scratch_cap) {
        uint8_t *s = realloc(h->scratch, (size_t) need);
        if (!s) return -1;
        h->scratch = s;
        h->scratch_cap = need;
    }
    int n = h->d->read(h->d, h->scratch, frames);
    if (n < 0) return -1;
    (*env)->SetByteArrayRegion(env, buf, off, n * fb, (const jbyte *) h->scratch);
    return n * fb;
}

JNIEXPORT void JNICALL JNI_FN(close)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    Handle *h = (Handle *) (intptr_t) handle;
    if (!h) return;
    h->d->close(h->d);
    free(h->scratch);
    free(h);
}
