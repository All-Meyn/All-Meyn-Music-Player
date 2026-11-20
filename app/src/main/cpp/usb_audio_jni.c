// JNI bridge to libusb for the All-Meyn USB audio driver.
//
// Stage 1: prove that libusb can wrap the file descriptor Android hands us for the DAC and read
// its descriptors. Streaming (isochronous transfers) comes in stage 2.

#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <libusb.h>

static const char *speed_name(int speed) {
    switch (speed) {
        case LIBUSB_SPEED_LOW: return "Low (1.5 Mbit/s)";
        case LIBUSB_SPEED_FULL: return "Full (12 Mbit/s)";
        case LIBUSB_SPEED_HIGH: return "High (480 Mbit/s)";
        case LIBUSB_SPEED_SUPER: return "Super (5 Gbit/s)";
        case LIBUSB_SPEED_SUPER_PLUS: return "Super+ (10 Gbit/s)";
        default: return "Unknown";
    }
}

JNIEXPORT jstring JNICALL
Java_com_allmeyn_player_usb_NativeUsb_probe(JNIEnv *env, jclass clazz, jint fd) {
    (void) clazz;
    char out[1024];
    size_t n = 0;
    const struct libusb_version *v = libusb_get_version();
    n += snprintf(out + n, sizeof(out) - n, "libusb %u.%u.%u\n", v->major, v->minor, v->micro);

    // Android apps can't enumerate /dev/bus/usb; only use the fd UsbManager gave us.
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);

    libusb_context *ctx = NULL;
    int rc = libusb_init_context(&ctx, NULL, 0);
    if (rc != LIBUSB_SUCCESS) {
        snprintf(out + n, sizeof(out) - n, "libusb_init failed: %s", libusb_error_name(rc));
        return (*env)->NewStringUTF(env, out);
    }

    libusb_device_handle *handle = NULL;
    rc = libusb_wrap_sys_device(ctx, (intptr_t) fd, &handle);
    if (rc != LIBUSB_SUCCESS || handle == NULL) {
        n += snprintf(out + n, sizeof(out) - n, "wrap_sys_device failed: %s", libusb_error_name(rc));
        libusb_exit(ctx);
        return (*env)->NewStringUTF(env, out);
    }

    libusb_device *dev = libusb_get_device(handle);
    struct libusb_device_descriptor dd;
    if (libusb_get_device_descriptor(dev, &dd) == LIBUSB_SUCCESS) {
        n += snprintf(out + n, sizeof(out) - n,
                      "Device %04x:%04x · USB %x.%02x · speed %s\n",
                      dd.idVendor, dd.idProduct, dd.bcdUSB >> 8, dd.bcdUSB & 0xFF,
                      speed_name(libusb_get_device_speed(dev)));
    }

    struct libusb_config_descriptor *cfg = NULL;
    if (libusb_get_active_config_descriptor(dev, &cfg) == LIBUSB_SUCCESS) {
        n += snprintf(out + n, sizeof(out) - n, "Active config %u · %u interfaces · fd wrap OK",
                      cfg->bConfigurationValue, cfg->bNumInterfaces);
        libusb_free_config_descriptor(cfg);
    } else {
        n += snprintf(out + n, sizeof(out) - n, "Could not read active config");
    }

    libusb_close(handle);
    libusb_exit(ctx);
    return (*env)->NewStringUTF(env, out);
}
