cmake_minimum_required(VERSION 3.22.1)
project(allmeyn_usb C CXX)

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

# ---- USB driver + file decoders (one JNI library) --------------------------------------------
add_library(allmeyn_usb SHARED
    usb_audio_jni.c usb_audio_engine.c usb_audio_core.c
    decoder/dsd.c decoder/ffdec.c decoder/decoder_jni.c
    dsp/dsp_chain.cpp dsp/dsp_jni.cpp
)
set_source_files_properties(dsp/dsp_chain.cpp dsp/dsp_jni.cpp PROPERTIES COMPILE_OPTIONS "-std=c++17;-O2;-fno-exceptions;-fno-rtti")
target_link_libraries(allmeyn_usb PRIVATE usb1.0 log m)

# ---- FFmpeg (APE/WavPack), optional: built by scripts/build-ffmpeg.sh --------------------------
set(FFMPEG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/${ANDROID_ABI})
if(EXISTS ${FFMPEG_DIR}/lib/libavformat.a)
    foreach(lib avformat avcodec avutil)
        add_library(${lib} STATIC IMPORTED)
        set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${FFMPEG_DIR}/lib/lib${lib}.a)
    endforeach()
    target_compile_definitions(allmeyn_usb PRIVATE HAVE_FFMPEG=1)
    target_include_directories(allmeyn_usb PRIVATE ${FFMPEG_DIR}/include)
    target_link_libraries(allmeyn_usb PRIVATE avformat avcodec avutil)
    # Keep FFmpeg's symbols private: the Media3 FFmpeg extension ships its own copy.
    target_link_options(allmeyn_usb PRIVATE "-Wl,--exclude-libs,ALL")
    message(STATUS "FFmpeg found for ${ANDROID_ABI}: APE/WavPack enabled")
else()
    message(WARNING "FFmpeg not built for ${ANDROID_ABI} (run scripts/build-ffmpeg.sh): APE/WavPack disabled")
endif()
