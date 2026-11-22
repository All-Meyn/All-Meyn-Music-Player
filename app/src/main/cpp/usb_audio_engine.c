// All-Meyn USB Audio Class 2 streaming engine.
//
// A long-lived *session* owns the DAC (interfaces claimed, Android's driver detached) and a libusb
// event thread. Inside the session, a *stream* runs at one sample rate / alt setting and keeps
// isochronous OUT transfers flowing, paced by the DAC's async feedback endpoint. Each packet is
// filled from the ring buffer (music, written by Media3's playback thread), from the tone
// generator (test), or with silence (paused / underrun).
//
// Threads: JNI control calls hold g_lock. The USB event thread runs all transfer callbacks and
// never takes g_lock; it talks to the rest through atomics and the lock-free ring.

#define _GNU_SOURCE  // gettid() on glibc; bionic always has it
#include <jni.h>
#include <libusb.h>
#include <android/log.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <unistd.h>

#include "usb_audio_core.h"

#define TAG "AllMeynUsb"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define NUM_TRANSFERS 8         // in flight
#define PACKETS_PER_TRANSFER 8  // 8 packets = 1 ms at high speed
#define TWO_PI 6.283185307179586

enum { SRC_RING = 0, SRC_TONE = 1 };

typedef struct Engine Engine;

typedef struct {
    Engine *e;
    uint32_t ring_frames;  // frames of music this transfer carried
    uint32_t gen;          // flush generation it was filled in
} XferCtx;

struct Engine {
    // ---- session (fixed while open) ----
    libusb_context *ctx;
    libusb_device_handle *h;
    int control_if, clock_id, stream_if;
    int ep_out, ep_fb, fb_max_packet, ep_interval;
    int high_speed;
    atomic_int session_alive;
    atomic_int dead;  // device vanished
    pthread_t thread;
    int thread_started;

    // ---- stream (changes on reconfigure; touched by callbacks only while streaming) ----
    int streaming;
    int alt, max_packet, subslot, out_channels, frame_bytes, rate;
    uint32_t confirmed_rate;
    int uframes_per_packet, packets_per_sec;
    double nominal_fpp;
    _Atomic double feedback_fpp;
    double frac_acc;
    struct libusb_transfer *out[NUM_TRANSFERS];
    XferCtx out_ctx[NUM_TRANSFERS];
    struct libusb_transfer *fb;
    uint8_t fb_buf[8];
    atomic_int stream_running;
    atomic_int active;

    // ---- source ----
    Ring ring;
    int in_format, in_channels;  // producer side only
    atomic_int idle_byte;        // fill for pauses/underruns: 0 for PCM, DSD_IDLE_BYTE for native DSD
    atomic_uint settle_frames;   // native DSD: DSD silence still to send after a flush (DSD_SETTLE_MS)
    atomic_int source;
    atomic_int playing;
    atomic_uint gen;
    _Atomic uint64_t played_frames;
    double phase, phase_inc;

    // ---- stats ----
    atomic_ulong packets_sent, fb_count, errors, underruns;
    UnderrunState ur;            // which short packets count as underruns (mid-stream dropouts only)
    char last_error[160];
};

static Engine *g = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static int fail(Engine *e, const char *what, int rc) {
    snprintf(e->last_error, sizeof(e->last_error), "%s: %s", what, libusb_error_name(rc));
    LOGE("%s", e->last_error);
    return rc;
}

// ---- packet filling (event thread) ----------------------------------------------------------------

static int fill_packet(Engine *e, uint8_t *buf, uint32_t *ring_frames_out) {
    double fb = atomic_load(&e->feedback_fpp);
    e->frac_acc += (fb > 0) ? fb : e->nominal_fpp;
    int frames = (int) e->frac_acc;
    e->frac_acc -= frames;
    int max_frames = e->max_packet / e->frame_bytes;
    if (frames > max_frames) frames = max_frames;
    int bytes = frames * e->frame_bytes;

    int got = 0;
    if (atomic_load(&e->playing)) {
        if (atomic_load(&e->source) == SRC_TONE) {
            for (int f = 0; f < frames; f++) {
                int32_t v = (int32_t) lrint(sin(e->phase) * 0.0631 * 2147483647.0);  // -24 dBFS
                e->phase += e->phase_inc;
                if (e->phase >= TWO_PI) e->phase -= TWO_PI;
                for (int c = 0; c < e->out_channels; c++) {
                    s32_to_slot(buf + f * e->frame_bytes + c * e->subslot, e->subslot, v);
                }
            }
            got = frames;
        } else if (settle_take(&e->settle_frames, (uint32_t) frames) > 0) {
            got = 0;  // native DSD settling after a flush: the whole packet is DSD silence
        } else {
            got = (int) ring_read(&e->ring, buf, (uint32_t) frames);
            *ring_frames_out += (uint32_t) got;
            if (underrun_note(&e->ur, got == frames)) atomic_fetch_add(&e->underruns, 1);
        }
    }
    if (got < frames) memset(buf + got * e->frame_bytes, atomic_load(&e->idle_byte), (size_t) (frames - got) * e->frame_bytes);
    return bytes;
}

static int fill_and_submit_out(Engine *e, struct libusb_transfer *t) {
    XferCtx *x = (XferCtx *) t->user_data;
    x->ring_frames = 0;
    x->gen = atomic_load(&e->gen);
    int offset = 0;
    for (int i = 0; i < t->num_iso_packets; i++) {
        int len = fill_packet(e, t->buffer + offset, &x->ring_frames);
        t->iso_packet_desc[i].length = (unsigned int) len;
        offset += len;
    }
    t->length = offset;
    int rc = libusb_submit_transfer(t);
    if (rc == LIBUSB_SUCCESS) atomic_fetch_add(&e->packets_sent, (unsigned long) t->num_iso_packets);
    return rc;
}

static void LIBUSB_CALL out_callback(struct libusb_transfer *t) {
    XferCtx *x = (XferCtx *) t->user_data;
    Engine *e = x->e;
    if (t->status == LIBUSB_TRANSFER_COMPLETED && x->gen == atomic_load(&e->gen)) {
        // Data has left the host: count it as played (drives Media3's playback clock).
        atomic_fetch_add(&e->played_frames, x->ring_frames);
    }
    if (t->status == LIBUSB_TRANSFER_NO_DEVICE) atomic_store(&e->dead, 1);
    else if (t->status != LIBUSB_TRANSFER_COMPLETED && t->status != LIBUSB_TRANSFER_CANCELLED)
        atomic_fetch_add(&e->errors, 1);

    if (!atomic_load(&e->stream_running) || t->status == LIBUSB_TRANSFER_CANCELLED ||
        t->status == LIBUSB_TRANSFER_NO_DEVICE) {
        atomic_fetch_sub(&e->active, 1);
        return;
    }
    if (fill_and_submit_out(e, t) != LIBUSB_SUCCESS) {
        atomic_fetch_add(&e->errors, 1);
        atomic_fetch_sub(&e->active, 1);
    }
}

static void LIBUSB_CALL fb_callback(struct libusb_transfer *t) {
    Engine *e = (Engine *) t->user_data;
    if (t->status == LIBUSB_TRANSFER_COMPLETED) {
        int n = (int) t->iso_packet_desc[0].actual_length;
        const uint8_t *b = t->buffer;
        double value = 0;
        if (n >= 4) {  // high speed: Q16.16 frames per microframe
            value = ((uint32_t) b[0] | ((uint32_t) b[1] << 8) | ((uint32_t) b[2] << 16) | ((uint32_t) b[3] << 24)) / 65536.0;
        } else if (n == 3) {  // full speed: Q10.14 frames per frame
            value = ((uint32_t) b[0] | ((uint32_t) b[1] << 8) | ((uint32_t) b[2] << 16)) / 16384.0;
        }
        double fpp = value * e->uframes_per_packet;
        // Non-standard fixed-point positions: shift by powers of two (as Linux snd-usb-audio does).
        for (int k = 0; k < 4 && fpp > 0 && fpp < e->nominal_fpp * 0.5; k++) fpp *= 2;
        for (int k = 0; k < 4 && fpp > e->nominal_fpp * 1.5; k++) fpp /= 2;
        if (fpp > e->nominal_fpp * 0.875 && fpp < e->nominal_fpp * 1.125) {
            atomic_store(&e->feedback_fpp, fpp);
            atomic_fetch_add(&e->fb_count, 1);
        }
    } else if (t->status == LIBUSB_TRANSFER_NO_DEVICE) {
        atomic_store(&e->dead, 1);
    } else if (t->status != LIBUSB_TRANSFER_CANCELLED) {
        atomic_fetch_add(&e->errors, 1);
    }
    if (!atomic_load(&e->stream_running) || t->status == LIBUSB_TRANSFER_CANCELLED ||
        t->status == LIBUSB_TRANSFER_NO_DEVICE || libusb_submit_transfer(t) != LIBUSB_SUCCESS) {
        atomic_fetch_sub(&e->active, 1);
    }
}

static void *event_thread(void *arg) {
    Engine *e = (Engine *) arg;
    setpriority(PRIO_PROCESS, (id_t) gettid(), -19);  // THREAD_PRIORITY_URGENT_AUDIO
    struct timeval tv = {0, 50000};
    while (atomic_load(&e->session_alive) || atomic_load(&e->active) > 0) {
        libusb_handle_events_timeout_completed(e->ctx, &tv, NULL);
    }
    return NULL;
}

// ---- stream control (g_lock held) -----------------------------------------------------------------

static void stream_stop(Engine *e) {
    if (!e->streaming) return;
    atomic_store(&e->stream_running, 0);
    for (int i = 0; i < NUM_TRANSFERS; i++) if (e->out[i]) libusb_cancel_transfer(e->out[i]);
    if (e->fb) libusb_cancel_transfer(e->fb);
    // The event thread reaps the cancelled transfers; wait up to ~1 s.
    for (int i = 0; i < 1000 && atomic_load(&e->active) > 0; i++) usleep(1000);
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        if (e->out[i]) {
            free(e->out[i]->buffer);
            libusb_free_transfer(e->out[i]);
            e->out[i] = NULL;
        }
    }
    if (e->fb) {
        libusb_free_transfer(e->fb);
        e->fb = NULL;
    }
    libusb_set_interface_alt_setting(e->h, e->stream_if, 0);
    e->streaming = 0;
}

static int set_clock(Engine *e) {
    uint8_t d[4] = {(uint8_t) e->rate, (uint8_t) (e->rate >> 8), (uint8_t) (e->rate >> 16), (uint8_t) (e->rate >> 24)};
    uint16_t w_index = (uint16_t) ((e->clock_id << 8) | e->control_if);
    int rc = libusb_control_transfer(e->h, 0x21, 0x01, 0x0100, w_index, d, 4, 1000);  // SET CUR
    if (rc < 0) return fail(e, "set sample rate", rc);
    uint8_t r[4] = {0};
    rc = libusb_control_transfer(e->h, 0xA1, 0x01, 0x0100, w_index, r, 4, 1000);  // GET CUR
    e->confirmed_rate = rc == 4 ? (r[0] | (r[1] << 8) | (r[2] << 16) | ((uint32_t) r[3] << 24)) : 0;
    if (e->confirmed_rate != 0 && e->confirmed_rate != (uint32_t) e->rate) {
        snprintf(e->last_error, sizeof(e->last_error), "DAC refused %d Hz (reports %u Hz)",
                 e->rate, e->confirmed_rate);
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    return LIBUSB_SUCCESS;
}

static int stream_start(Engine *e) {
    libusb_set_interface_alt_setting(e->h, e->stream_if, 0);
    int rc = set_clock(e);
    if (rc) return rc;
    rc = libusb_set_interface_alt_setting(e->h, e->stream_if, e->alt);
    if (rc) return fail(e, "set alt setting", rc);

    e->uframes_per_packet = e->high_speed ? 1 << (e->ep_interval > 0 ? e->ep_interval - 1 : 0) : 1;
    e->packets_per_sec = e->high_speed ? 8000 / e->uframes_per_packet : 1000;
    e->nominal_fpp = (double) e->rate / e->packets_per_sec;
    atomic_store(&e->feedback_fpp, 0.0);
    e->frac_acc = 0;
    atomic_store(&e->stream_running, 1);
    e->streaming = 1;

    if (e->ep_fb > 0) {
        e->fb = libusb_alloc_transfer(1);
        int fb_len = e->fb_max_packet > 0 && e->fb_max_packet <= (int) sizeof(e->fb_buf) ? e->fb_max_packet : 4;
        libusb_fill_iso_transfer(e->fb, e->h, (unsigned char) e->ep_fb, e->fb_buf, fb_len, 1, fb_callback, e, 1000);
        libusb_set_iso_packet_lengths(e->fb, (unsigned int) fb_len);
        atomic_fetch_add(&e->active, 1);
        if (libusb_submit_transfer(e->fb) != LIBUSB_SUCCESS) {
            atomic_fetch_sub(&e->active, 1);
            atomic_fetch_add(&e->errors, 1);
        }
    }
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        struct libusb_transfer *t = libusb_alloc_transfer(PACKETS_PER_TRANSFER);
        uint8_t *buf = calloc((size_t) PACKETS_PER_TRANSFER, (size_t) e->max_packet);
        if (!t || !buf) {
            free(buf);
            if (t) libusb_free_transfer(t);
            stream_stop(e);
            return fail(e, "allocate transfers", LIBUSB_ERROR_NO_MEM);
        }
        e->out_ctx[i].e = e;
        libusb_fill_iso_transfer(t, e->h, (unsigned char) e->ep_out, buf,
                                 PACKETS_PER_TRANSFER * e->max_packet, PACKETS_PER_TRANSFER,
                                 out_callback, &e->out_ctx[i], 1000);
        e->out[i] = t;
        atomic_fetch_add(&e->active, 1);
        rc = fill_and_submit_out(e, t);
        if (rc) {
            atomic_fetch_sub(&e->active, 1);
            stream_stop(e);
            return fail(e, "submit iso OUT", rc);
        }
    }
    return LIBUSB_SUCCESS;
}

static void reset_position(Engine *e) {
    atomic_store(&e->playing, 0);
    underrun_restart(&e->ur);
    ring_flush(&e->ring);
    atomic_store(&e->settle_frames,
                 e->in_format == IN_DSD_NATIVE ? (unsigned) ((int64_t) e->rate * DSD_SETTLE_MS / 1000) : 0u);
    atomic_fetch_add(&e->gen, 1);
    atomic_store(&e->played_frames, 0);
}

static void session_close(Engine *e) {
    if (!e) return;
    if (e->h) stream_stop(e);
    atomic_store(&e->session_alive, 0);
    if (e->thread_started) pthread_join(e->thread, NULL);
    if (e->h) {
        // Auto-detach is on: releasing hands the interfaces back to Android's driver.
        libusb_release_interface(e->h, e->stream_if);
        libusb_release_interface(e->h, e->control_if);
        libusb_close(e->h);
    }
    if (e->ctx) libusb_exit(e->ctx);
    ring_free(&e->ring);
    free(e);
}

// ---- JNI ----------------------------------------------------------------------------------------

#define JNI_FN(name) Java_com_allmeyn_player_usb_NativeUsb_##name

JNIEXPORT jstring JNICALL
JNI_FN(open)(JNIEnv *env, jclass clazz, jint fd, jint control_if, jint clock_id, jint stream_if,
             jint ep_out, jint ep_fb, jint fb_max_packet, jint ep_interval) {
    (void) clazz;
    pthread_mutex_lock(&g_lock);
    session_close(g);
    g = NULL;
    Engine *e = calloc(1, sizeof(Engine));
    e->control_if = control_if; e->clock_id = clock_id; e->stream_if = stream_if;
    e->ep_out = ep_out; e->ep_fb = ep_fb; e->fb_max_packet = fb_max_packet; e->ep_interval = ep_interval;

    char msg[200];
    int rc;
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if ((rc = libusb_init_context(&e->ctx, NULL, 0))) fail(e, "libusb init", rc);
    else if ((rc = libusb_wrap_sys_device(e->ctx, (intptr_t) fd, &e->h))) fail(e, "wrap fd", rc);
    else {
        libusb_set_auto_detach_kernel_driver(e->h, 1);
        if ((rc = libusb_claim_interface(e->h, control_if))) fail(e, "claim control interface", rc);
        else if ((rc = libusb_claim_interface(e->h, stream_if))) fail(e, "claim streaming interface", rc);
    }
    if (rc == 0) {
        e->high_speed = libusb_get_device_speed(libusb_get_device(e->h)) >= LIBUSB_SPEED_HIGH;
        atomic_store(&e->session_alive, 1);
        if (pthread_create(&e->thread, NULL, event_thread, e) != 0) {
            rc = fail(e, "start event thread", LIBUSB_ERROR_OTHER);
        } else {
            e->thread_started = 1;
        }
    }
    if (rc) {
        snprintf(msg, sizeof(msg), "ERROR %s", e->last_error);
        if (e->h) {
            libusb_release_interface(e->h, stream_if);
            libusb_release_interface(e->h, control_if);
            libusb_close(e->h);
            e->h = NULL;
        }
        atomic_store(&e->session_alive, 0);
        session_close(e);
    } else {
        snprintf(msg, sizeof(msg), "OK");
        g = e;
    }
    pthread_mutex_unlock(&g_lock);
    return (*env)->NewStringUTF(env, msg);
}

JNIEXPORT jstring JNICALL
JNI_FN(configure)(JNIEnv *env, jclass clazz, jint alt, jint max_packet, jint subslot,
                  jint out_channels, jint rate, jint in_format, jint in_channels, jint ring_ms) {
    (void) clazz;
    char msg[200];
    pthread_mutex_lock(&g_lock);
    Engine *e = g;
    // These come from the DAC's descriptors: a faulty (or hostile) device must not be able to
    // make the stream divide by zero or size its buffers from nonsense.
    int sane = (subslot >= 2 && subslot <= 4) && out_channels >= 1 && out_channels <= 8 &&
               max_packet >= subslot * out_channels && max_packet <= 3 * 1024 &&
               rate >= 8000 && rate <= 1536000 && in_channels >= 1 && in_channels <= 8 &&
               ring_ms >= 10 && ring_ms <= 2000;
    if (!e) {
        snprintf(msg, sizeof(msg), "ERROR driver not open");
    } else if (!sane) {
        snprintf(msg, sizeof(msg), "ERROR unsupported stream (%d ch, %d-byte slots, %d Hz, packet %d)",
                 out_channels, subslot, rate, max_packet);
    } else if (atomic_load(&e->dead)) {
        snprintf(msg, sizeof(msg), "ERROR DAC disconnected");
    } else {
        int same = e->streaming && e->alt == alt && e->rate == rate && e->subslot == subslot &&
                   e->out_channels == out_channels && e->max_packet == max_packet;
        int rc = 0;
        if (!same) {
            stream_stop(e);
            e->alt = alt; e->max_packet = max_packet; e->subslot = subslot;
            e->out_channels = out_channels; e->rate = rate;
            e->frame_bytes = subslot * out_channels;
            ring_free(&e->ring);
            uint32_t frames = (uint32_t) ((int64_t) rate * ring_ms / 1000);
            if (ring_init(&e->ring, frames, (uint32_t) e->frame_bytes) != 0) {
                rc = fail(e, "allocate ring", LIBUSB_ERROR_NO_MEM);
            }
        }
        e->in_format = in_format;
        e->in_channels = in_channels;
        atomic_store(&e->idle_byte, in_format == IN_DSD_NATIVE ? DSD_IDLE_BYTE : 0);
        atomic_store(&e->source, SRC_RING);
        reset_position(e);
        if (rc == 0 && !same) rc = stream_start(e);
        if (rc) snprintf(msg, sizeof(msg), "ERROR %s", e->last_error);
        else snprintf(msg, sizeof(msg), "OK %u", e->confirmed_rate);
    }
    pthread_mutex_unlock(&g_lock);
    return (*env)->NewStringUTF(env, msg);
}

JNIEXPORT jint JNICALL
JNI_FN(write)(JNIEnv *env, jclass clazz, jobject buffer, jint offset, jint length) {
    (void) clazz;
    jint result;
    pthread_mutex_lock(&g_lock);
    Engine *e = g;
    uint8_t *base = (uint8_t *) (*env)->GetDirectBufferAddress(env, buffer);
    jlong cap = base ? (*env)->GetDirectBufferCapacity(env, buffer) : 0;
    if (!e || !e->streaming || atomic_load(&e->dead) || !base ||
        offset < 0 || length < 0 || (jlong) offset + length > cap) {
        result = -1;
    } else {
        result = (jint) ring_write_converted(&e->ring, base + offset, (size_t) length, e->in_format,
                                             e->in_channels, e->subslot, e->out_channels);
    }
    pthread_mutex_unlock(&g_lock);
    return result;
}

JNIEXPORT void JNICALL JNI_FN(play)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    pthread_mutex_lock(&g_lock);
    if (g) atomic_store(&g->playing, 1);
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL JNI_FN(pause)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    pthread_mutex_lock(&g_lock);
    if (g) {
        atomic_store(&g->playing, 0);
        underrun_restart(&g->ur);
    }
    pthread_mutex_unlock(&g_lock);
}

/** The player is playing out the end of the queue: the ring running dry now isn't a dropout. */
JNIEXPORT void JNICALL JNI_FN(endOfStream)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    pthread_mutex_lock(&g_lock);
    if (g) underrun_end_of_stream(&g->ur);
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL JNI_FN(flush)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    pthread_mutex_lock(&g_lock);
    if (g && g->streaming) reset_position(g);
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jlong JNICALL JNI_FN(playedFrames)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    // Locked: close() on another thread may free the engine.
    pthread_mutex_lock(&g_lock);
    jlong n = g ? (jlong) atomic_load(&g->played_frames) : 0;
    pthread_mutex_unlock(&g_lock);
    return n;
}

JNIEXPORT jint JNICALL JNI_FN(ringFrames)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    pthread_mutex_lock(&g_lock);
    jint n = g ? (jint) g->ring.capacity_frames : 0;
    pthread_mutex_unlock(&g_lock);
    return n;
}

JNIEXPORT jboolean JNICALL JNI_FN(isAlive)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    pthread_mutex_lock(&g_lock);
    jboolean alive = g != NULL && !atomic_load(&g->dead);
    pthread_mutex_unlock(&g_lock);
    return alive;
}

JNIEXPORT jstring JNICALL
JNI_FN(startTone)(JNIEnv *env, jclass clazz, jint alt, jint max_packet, jint subslot,
                  jint out_channels, jint rate, jdouble tone_hz) {
    jstring res = JNI_FN(configure)(env, clazz, alt, max_packet, subslot, out_channels, rate,
                                    IN_S32, out_channels, 100);
    pthread_mutex_lock(&g_lock);
    if (g && g->streaming) {
        g->phase = 0;
        g->phase_inc = TWO_PI * tone_hz / rate;
        atomic_store(&g->source, SRC_TONE);
        atomic_store(&g->playing, 1);
    }
    pthread_mutex_unlock(&g_lock);
    return res;
}

JNIEXPORT void JNICALL JNI_FN(close)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    pthread_mutex_lock(&g_lock);
    session_close(g);
    g = NULL;
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jstring JNICALL JNI_FN(status)(JNIEnv *env, jclass clazz) {
    (void) clazz;
    char msg[320];
    pthread_mutex_lock(&g_lock);
    Engine *e = g;
    if (!e) {
        snprintf(msg, sizeof(msg), "Driver stopped");
    } else if (!e->streaming) {
        snprintf(msg, sizeof(msg), "DAC claimed · idle%s", atomic_load(&e->dead) ? " · DISCONNECTED" : "");
    } else {
        snprintf(msg, sizeof(msg),
                 "%s · %s · %d Hz · %d-bit slot · alt %d%s\n"
                 "buffer %u/%u fr · played %llu fr · feedback %.4f (nominal %.4f) · dropouts %lu · errors %lu",
                 atomic_load(&e->source) == SRC_TONE ? "Tone" : "Music",
                 atomic_load(&e->playing) ? "playing" : "paused",
                 e->rate, e->subslot * 8, e->alt, atomic_load(&e->dead) ? " · DISCONNECTED" : "",
                 ring_available(&e->ring), e->ring.capacity_frames,
                 (unsigned long long) atomic_load(&e->played_frames),
                 atomic_load(&e->feedback_fpp), e->nominal_fpp,
                 atomic_load(&e->underruns), atomic_load(&e->errors));
    }
    pthread_mutex_unlock(&g_lock);
    return (*env)->NewStringUTF(env, msg);
}
