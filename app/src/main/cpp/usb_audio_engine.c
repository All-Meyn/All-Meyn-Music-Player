// All-Meyn USB Audio Class 2 streaming engine.
//
// Stage 2: takes the DAC away from Android's kernel driver, sets the sample rate on the UAC2
// clock source, and streams a sine test tone over isochronous OUT transfers, pacing itself with
// the DAC's asynchronous feedback endpoint. Stage 3 swaps the tone for a ring buffer of music.
//
// Threading: JNI calls (start/stop/status) come from a Kotlin worker thread. All libusb transfer
// callbacks run on this engine's own event thread.

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

#define TAG "AllMeynUsb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define NUM_TRANSFERS 8         // in flight
#define PACKETS_PER_TRANSFER 8  // 8 packets = 1 ms at high speed
#define TWO_PI 6.283185307179586

typedef struct {
    libusb_context *ctx;
    libusb_device_handle *h;
    int control_if, stream_if, alt;
    int ep_out, ep_fb, max_packet, fb_max_packet;
    int subslot, channels, frame_bytes;
    int rate;
    uint32_t confirmed_rate;
    int packets_per_sec;
    int uframes_per_packet;  // bus intervals per packet (feedback is per bus interval)
    double nominal_fpp;      // frames per packet
    _Atomic double feedback_fpp;  // 0 until first valid feedback
    double frac_acc;

    // Tone generator (stage 2 source).
    double phase, phase_inc, amp;

    struct libusb_transfer *out[NUM_TRANSFERS];
    struct libusb_transfer *fb;
    uint8_t fb_buf[8];

    atomic_int running;
    atomic_int active;  // transfers submitted and not yet returned
    pthread_t thread;
    int thread_started;

    atomic_ulong packets_sent, frames_sent, fb_count, errors;
    char last_error[128];
} Engine;

static Engine *g_engine = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

// ---- sample source ------------------------------------------------------------------------------

static void write_sample(uint8_t *p, int subslot, double s) {
    if (s > 1.0) s = 1.0;
    if (s < -1.0) s = -1.0;
    if (subslot == 2) {
        int16_t v = (int16_t) lrint(s * 32767.0);
        memcpy(p, &v, 2);  // Android ABIs are little-endian, as USB audio expects.
    } else if (subslot == 3) {
        int32_t v = (int32_t) lrint(s * 8388607.0);
        p[0] = (uint8_t) v; p[1] = (uint8_t) (v >> 8); p[2] = (uint8_t) (v >> 16);
    } else {  // 4-byte slot, left-justified
        int32_t v = (int32_t) lrint(s * 2147483647.0);
        memcpy(p, &v, 4);
    }
}

/** Fills one packet; returns its length in bytes. */
static int fill_packet(Engine *e, uint8_t *buf) {
    double fb = atomic_load(&e->feedback_fpp);
    e->frac_acc += (fb > 0) ? fb : e->nominal_fpp;
    int frames = (int) e->frac_acc;
    e->frac_acc -= frames;
    int max_frames = e->max_packet / e->frame_bytes;
    if (frames > max_frames) frames = max_frames;

    uint8_t *p = buf;
    for (int f = 0; f < frames; f++) {
        double s = sin(e->phase) * e->amp;
        e->phase += e->phase_inc;
        if (e->phase >= TWO_PI) e->phase -= TWO_PI;
        for (int c = 0; c < e->channels; c++) {
            write_sample(p, e->subslot, s);
            p += e->subslot;
        }
    }
    atomic_fetch_add(&e->frames_sent, (unsigned long) frames);
    return frames * e->frame_bytes;
}

// ---- transfers ----------------------------------------------------------------------------------

static int fill_and_submit_out(Engine *e, struct libusb_transfer *t) {
    int offset = 0;
    for (int i = 0; i < t->num_iso_packets; i++) {
        int len = fill_packet(e, t->buffer + offset);
        t->iso_packet_desc[i].length = (unsigned int) len;
        offset += len;
    }
    t->length = offset;
    int rc = libusb_submit_transfer(t);
    if (rc == LIBUSB_SUCCESS) {
        atomic_fetch_add(&e->packets_sent, (unsigned long) t->num_iso_packets);
    }
    return rc;
}

static void LIBUSB_CALL out_callback(struct libusb_transfer *t) {
    Engine *e = (Engine *) t->user_data;
    if (t->status != LIBUSB_TRANSFER_COMPLETED && t->status != LIBUSB_TRANSFER_CANCELLED) {
        atomic_fetch_add(&e->errors, 1);
    }
    if (!atomic_load(&e->running) || t->status == LIBUSB_TRANSFER_CANCELLED ||
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
        if (n >= 4) {         // high speed: Q16.16 frames per microframe
            uint32_t q = (uint32_t) b[0] | ((uint32_t) b[1] << 8) | ((uint32_t) b[2] << 16) | ((uint32_t) b[3] << 24);
            value = q / 65536.0;
        } else if (n == 3) {  // full speed: Q10.14 frames per frame
            uint32_t q = (uint32_t) b[0] | ((uint32_t) b[1] << 8) | ((uint32_t) b[2] << 16);
            value = q / 16384.0;
        }
        double fpp = value * e->uframes_per_packet;
        // Some DACs use a non-standard fixed-point position (Linux's snd-usb-audio corrects for
        // this the same way): shift by powers of two until it lands near nominal.
        for (int k = 0; k < 4 && fpp > 0 && fpp < e->nominal_fpp * 0.5; k++) fpp *= 2;
        for (int k = 0; k < 4 && fpp > e->nominal_fpp * 1.5; k++) fpp /= 2;
        // Ignore garbage: accept only within ±12.5% of nominal.
        if (fpp > e->nominal_fpp * 0.875 && fpp < e->nominal_fpp * 1.125) {
            atomic_store(&e->feedback_fpp, fpp);
            atomic_fetch_add(&e->fb_count, 1);
        }
    } else if (t->status != LIBUSB_TRANSFER_CANCELLED) {
        atomic_fetch_add(&e->errors, 1);
    }
    if (!atomic_load(&e->running) || t->status == LIBUSB_TRANSFER_CANCELLED ||
        t->status == LIBUSB_TRANSFER_NO_DEVICE || libusb_submit_transfer(t) != LIBUSB_SUCCESS) {
        atomic_fetch_sub(&e->active, 1);
    }
}

static void *event_thread(void *arg) {
    Engine *e = (Engine *) arg;
    // Same priority Android gives its own audio threads (THREAD_PRIORITY_URGENT_AUDIO).
    setpriority(PRIO_PROCESS, (id_t) gettid(), -19);
    struct timeval tv = {0, 100000};
    while (atomic_load(&e->running) || atomic_load(&e->active) > 0) {
        libusb_handle_events_timeout_completed(e->ctx, &tv, NULL);
    }
    return NULL;
}

// ---- lifecycle ----------------------------------------------------------------------------------

static void engine_teardown(Engine *e) {
    atomic_store(&e->running, 0);
    for (int i = 0; i < NUM_TRANSFERS; i++) if (e->out[i]) libusb_cancel_transfer(e->out[i]);
    if (e->fb) libusb_cancel_transfer(e->fb);
    if (e->thread_started) {
        pthread_join(e->thread, NULL);
    } else if (e->ctx) {
        // Start failed after some transfers were submitted: reap them before freeing (max ~1 s).
        struct timeval tv = {0, 50000};
        for (int tries = 0; atomic_load(&e->active) > 0 && tries < 20; tries++) {
            libusb_handle_events_timeout_completed(e->ctx, &tv, NULL);
        }
    }
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        if (e->out[i]) {
            free(e->out[i]->buffer);
            libusb_free_transfer(e->out[i]);
        }
    }
    if (e->fb) libusb_free_transfer(e->fb);
    if (e->h) {
        libusb_set_interface_alt_setting(e->h, e->stream_if, 0);
        // Auto-detach is on, so releasing hands the interfaces back to Android's driver.
        libusb_release_interface(e->h, e->stream_if);
        libusb_release_interface(e->h, e->control_if);
        libusb_close(e->h);
    }
    if (e->ctx) libusb_exit(e->ctx);
    free(e);
}

static int fail(Engine *e, const char *what, int rc) {
    snprintf(e->last_error, sizeof(e->last_error), "%s: %s", what, libusb_error_name(rc));
    LOGE("%s", e->last_error);
    return rc;
}

static int set_clock(Engine *e, int clock_id) {
    uint8_t d[4] = {(uint8_t) e->rate, (uint8_t) (e->rate >> 8), (uint8_t) (e->rate >> 16), (uint8_t) (e->rate >> 24)};
    uint16_t w_index = (uint16_t) ((clock_id << 8) | e->control_if);
    // SET CUR, CS_SAM_FREQ_CONTROL
    int rc = libusb_control_transfer(e->h, 0x21, 0x01, 0x0100, w_index, d, 4, 1000);
    if (rc < 0) return fail(e, "set sample rate", rc);
    uint8_t r[4] = {0};
    rc = libusb_control_transfer(e->h, 0xA1, 0x01, 0x0100, w_index, r, 4, 1000);
    if (rc == 4) e->confirmed_rate = r[0] | (r[1] << 8) | (r[2] << 16) | ((uint32_t) r[3] << 24);
    return LIBUSB_SUCCESS;
}

static int engine_start(Engine *e, int fd, int clock_id, int ep_interval, double tone_hz) {
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    int rc = libusb_init_context(&e->ctx, NULL, 0);
    if (rc) return fail(e, "libusb init", rc);
    rc = libusb_wrap_sys_device(e->ctx, (intptr_t) fd, &e->h);
    if (rc) return fail(e, "wrap fd", rc);

    libusb_set_auto_detach_kernel_driver(e->h, 1);
    rc = libusb_claim_interface(e->h, e->control_if);
    if (rc) return fail(e, "claim control interface", rc);
    rc = libusb_claim_interface(e->h, e->stream_if);
    if (rc) return fail(e, "claim streaming interface", rc);

    libusb_set_interface_alt_setting(e->h, e->stream_if, 0);
    rc = set_clock(e, clock_id);
    if (rc) return rc;
    rc = libusb_set_interface_alt_setting(e->h, e->stream_if, e->alt);
    if (rc) return fail(e, "set alt setting", rc);

    int speed = libusb_get_device_speed(libusb_get_device(e->h));
    if (speed >= LIBUSB_SPEED_HIGH) {
        int shift = ep_interval > 0 ? ep_interval - 1 : 0;
        e->uframes_per_packet = 1 << shift;
        e->packets_per_sec = 8000 / e->uframes_per_packet;
    } else {
        e->uframes_per_packet = 1;
        e->packets_per_sec = 1000;
    }
    e->frame_bytes = e->subslot * e->channels;
    e->nominal_fpp = (double) e->rate / e->packets_per_sec;
    e->phase_inc = TWO_PI * tone_hz / e->rate;
    e->amp = 0.0631;  // -24 dBFS: audible but gentle (Android volume doesn't apply here)
    atomic_store(&e->running, 1);

    // Feedback IN endpoint.
    if (e->ep_fb > 0) {
        e->fb = libusb_alloc_transfer(1);
        int fb_len = e->fb_max_packet > 0 && e->fb_max_packet <= (int) sizeof(e->fb_buf) ? e->fb_max_packet : 4;
        libusb_fill_iso_transfer(e->fb, e->h, (unsigned char) e->ep_fb, e->fb_buf, fb_len, 1, fb_callback, e, 1000);
        libusb_set_iso_packet_lengths(e->fb, (unsigned int) fb_len);
        if (libusb_submit_transfer(e->fb) == LIBUSB_SUCCESS) atomic_fetch_add(&e->active, 1);
        else atomic_fetch_add(&e->errors, 1);
    }

    // Data OUT endpoint.
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        struct libusb_transfer *t = libusb_alloc_transfer(PACKETS_PER_TRANSFER);
        uint8_t *buf = calloc((size_t) PACKETS_PER_TRANSFER, (size_t) e->max_packet);
        if (!t || !buf) return fail(e, "allocate transfers", LIBUSB_ERROR_NO_MEM);
        libusb_fill_iso_transfer(t, e->h, (unsigned char) e->ep_out, buf,
                                 PACKETS_PER_TRANSFER * e->max_packet, PACKETS_PER_TRANSFER,
                                 out_callback, e, 1000);
        e->out[i] = t;
        rc = fill_and_submit_out(e, t);
        if (rc) return fail(e, "submit iso OUT", rc);
        atomic_fetch_add(&e->active, 1);
    }

    if (pthread_create(&e->thread, NULL, event_thread, e) != 0) {
        return fail(e, "start event thread", LIBUSB_ERROR_OTHER);
    }
    e->thread_started = 1;
    return LIBUSB_SUCCESS;
}

// ---- JNI ----------------------------------------------------------------------------------------

JNIEXPORT jstring JNICALL
Java_com_allmeyn_player_usb_NativeUsb_startTone(
        JNIEnv *env, jclass clazz, jint fd, jint control_if, jint clock_id, jint stream_if,
        jint alt, jint ep_out, jint ep_fb, jint max_packet, jint fb_max_packet, jint ep_interval,
        jint subslot, jint channels, jint rate, jdouble tone_hz) {
    (void) clazz;
    pthread_mutex_lock(&g_lock);
    if (g_engine) {
        engine_teardown(g_engine);
        g_engine = NULL;
    }
    Engine *e = calloc(1, sizeof(Engine));
    e->control_if = control_if; e->stream_if = stream_if; e->alt = alt;
    e->ep_out = ep_out; e->ep_fb = ep_fb; e->max_packet = max_packet; e->fb_max_packet = fb_max_packet;
    e->subslot = subslot; e->channels = channels; e->rate = rate;

    char msg[192];
    int rc = engine_start(e, fd, clock_id, ep_interval, tone_hz);
    if (rc != LIBUSB_SUCCESS) {
        snprintf(msg, sizeof(msg), "ERROR %s", e->last_error);
        engine_teardown(e);
    } else {
        snprintf(msg, sizeof(msg), "Streaming %d Hz tone · requested %d Hz · DAC reports %u Hz",
                 (int) tone_hz, rate, e->confirmed_rate);
        g_engine = e;
    }
    pthread_mutex_unlock(&g_lock);
    return (*env)->NewStringUTF(env, msg);
}

JNIEXPORT void JNICALL
Java_com_allmeyn_player_usb_NativeUsb_stop(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    pthread_mutex_lock(&g_lock);
    if (g_engine) {
        engine_teardown(g_engine);
        g_engine = NULL;
    }
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jstring JNICALL
Java_com_allmeyn_player_usb_NativeUsb_status(JNIEnv *env, jclass clazz) {
    (void) clazz;
    char msg[256];
    pthread_mutex_lock(&g_lock);
    Engine *e = g_engine;
    if (!e) {
        snprintf(msg, sizeof(msg), "Driver stopped");
    } else {
        double fb = atomic_load(&e->feedback_fpp);
        snprintf(msg, sizeof(msg),
                 "%s · %d Hz · %d-bit slot\npackets %lu · frames %lu · feedback %lu (%.4f fr/pkt, nominal %.4f) · errors %lu · in-flight %d",
                 atomic_load(&e->running) ? "Running" : "Stopping", e->rate, e->subslot * 8,
                 atomic_load(&e->packets_sent), atomic_load(&e->frames_sent),
                 atomic_load(&e->fb_count), fb, e->nominal_fpp,
                 atomic_load(&e->errors), atomic_load(&e->active));
    }
    pthread_mutex_unlock(&g_lock);
    return (*env)->NewStringUTF(env, msg);
}
