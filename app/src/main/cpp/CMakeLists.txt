cmake_minimum_required(VERSION 3.22.1)
project(allmeyn_usb C)

# ---- libusb (vendored, LGPL-2.1) ----------------------------------------------------------------
set(LIBUSB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libusb)

add_library(usb1.0 STATIC
    ${LIBUSB_DIR}/libusb/core.c
    ${LIBUSB_DIR}/libusb/descriptor.c
    ${LIBUSB_DIR}/libusb/hotplug.c
    ${LIBUSB_DIR}/libusb/io.c
    ${LIBUSB_DIR}/libusb/sync.c
    ${LIBUSB_DIR}/libusb/strerror.c
    ${LIBUSB_DIR}/libusb/os/linux_usbfs.c
    ${LIBUSB_DIR}/libusb/os/events_posix.c
    ${LIBUSB_DIR}/libusb/os/threads_posix.c
    ${LIBUSB_DIR}/libusb/os/linux_netlink.c
)
target_include_directories(usb1.0
    PRIVATE ${LIBUSB_DIR}/android ${LIBUSB_DIR}/libusb/os
    PUBLIC ${LIBUSB_DIR}/libusb
)
target_compile_options(usb1.0 PRIVATE -fvisibility=hidden -pthread)
target_link_libraries(usb1.0 PRIVATE log)

# ---- JNI bridge -------------------------------------------------------------------------------
add_library(allmeyn_usb SHARED usb_audio_jni.c)
target_link_libraries(allmeyn_usb PRIVATE usb1.0 log)
