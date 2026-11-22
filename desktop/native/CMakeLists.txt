# The desktop audio engine (Windows x64): allmeyn_desktop.dll, loaded by the Compose Desktop app.
# Cross-compiled with MinGW-w64 (see cmake/mingw-w64.cmake), statically linked (no MinGW runtime
# DLLs to ship), against our FFmpeg build (scripts/build-ffmpeg-windows.sh).
#
#   cmake -S desktop/native -B build/desktop-native -DCMAKE_TOOLCHAIN_FILE=desktop/native/cmake/mingw-w64.cmake \
#         -DJDK_INCLUDE=<jdk>/include
#   cmake --build build/desktop-native
cmake_minimum_required(VERSION 3.22)
project(allmeyn_desktop C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

set(JDK_INCLUDE "" CACHE PATH "JDK include directory (for jni.h)")
if(NOT EXISTS "${JDK_INCLUDE}/jni.h")
    message(FATAL_ERROR "Set -DJDK_INCLUDE=<jdk>/include (jni.h not found in '${JDK_INCLUDE}')")
endif()

set(FFMPEG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/windows-x64")
if(NOT EXISTS "${FFMPEG_DIR}/lib/libavformat.a")
    message(FATAL_ERROR "FFmpeg not built: run scripts/build-ffmpeg-windows.sh")
endif()

add_library(allmeyn_desktop SHARED
    engine_jni.cpp
    audio_devices.cpp
)
target_include_directories(allmeyn_desktop PRIVATE
    "${JDK_INCLUDE}"
    "${CMAKE_CURRENT_SOURCE_DIR}/jni/win32"   # Windows jni_md.h (the host JDK's is for Linux)
    "${FFMPEG_DIR}/include"
)
target_compile_options(allmeyn_desktop PRIVATE -Wall -Wextra -Werror -O2)
target_link_libraries(allmeyn_desktop PRIVATE
    "${FFMPEG_DIR}/lib/libavformat.a" "${FFMPEG_DIR}/lib/libavcodec.a"
    "${FFMPEG_DIR}/lib/libswresample.a" "${FFMPEG_DIR}/lib/libavutil.a"
    ole32 uuid bcrypt   # COM / WASAPI; bcrypt: FFmpeg's random seed on Windows
)
# No lib prefix (System.loadLibrary("allmeyn_desktop")), no MinGW runtime DLLs, JNI symbols exported
# undecorated (JNICALL is __stdcall, a no-op on x64).
set_target_properties(allmeyn_desktop PROPERTIES PREFIX "")
target_link_options(allmeyn_desktop PRIVATE -static -static-libgcc -static-libstdc++ -Wl,--kill-at -s)
