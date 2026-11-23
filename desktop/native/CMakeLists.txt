# The desktop audio engine.
#  - Windows x64: allmeyn_desktop.dll, loaded by the Compose Desktop app. Cross-compiled with
#    MinGW-w64 (cmake/mingw-w64.cmake), statically linked (no MinGW runtime DLLs to ship).
#  - Linux (host): the same core (sources + player) as a test program, so decoding and seeking are
#    checked against real files without Windows (-DALLMEYN_HOST_TESTS=ON).
# FFmpeg comes from scripts/build-ffmpeg-desktop.sh <windows|linux>.
#
#   cmake -S desktop/native -B build/desktop-native -DCMAKE_TOOLCHAIN_FILE=desktop/native/cmake/mingw-w64.cmake \
#         -DJDK_INCLUDE=<jdk>/include
#   cmake -S desktop/native -B build/desktop-tests -DALLMEYN_HOST_TESTS=ON
cmake_minimum_required(VERSION 3.22)
project(allmeyn_desktop C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

option(ALLMEYN_HOST_TESTS "Build the core tests for the host (Linux)" OFF)
set(SHARED_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")  # the Android app's native code

if(WIN32)
    set(FFMPEG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/windows-x64")
else()
    set(FFMPEG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/linux-x64")
endif()
if(NOT EXISTS "${FFMPEG_DIR}/lib/libavformat.a")
    message(FATAL_ERROR "FFmpeg not built: run scripts/build-ffmpeg-desktop.sh (${FFMPEG_DIR})")
endif()

# ---- core: sources (FFmpeg + the Android app's DSD decoder) and the player -------------------------
add_library(allmeyn_core STATIC
    core/source.cpp
    core/player.cpp
    core/tags.cpp
    core/dsp_stage.cpp
    platform/file_open.cpp
    "${SHARED_CPP}/decoder/dsd.c"
    "${SHARED_CPP}/dsp/dsp_chain.cpp"
)
target_include_directories(allmeyn_core PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${FFMPEG_DIR}/include"
    "${SHARED_CPP}/decoder"
    "${SHARED_CPP}/dsp"
)
target_compile_definitions(allmeyn_core PRIVATE _FILE_OFFSET_BITS=64)
set_source_files_properties(core/source.cpp core/player.cpp core/tags.cpp core/dsp_stage.cpp platform/file_open.cpp
    PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra;-Werror")
if(WIN32)
    # dsd.c reads with pread(); Windows gets ours, so the shared file stays untouched.
    set_source_files_properties("${SHARED_CPP}/decoder/dsd.c" PROPERTIES COMPILE_OPTIONS
        "-include;${CMAKE_CURRENT_SOURCE_DIR}/platform/pread_win.h")
endif()
target_link_libraries(allmeyn_core PUBLIC
    "${FFMPEG_DIR}/lib/libavformat.a" "${FFMPEG_DIR}/lib/libavcodec.a"
    "${FFMPEG_DIR}/lib/libswresample.a" "${FFMPEG_DIR}/lib/libavutil.a"
)
if(WIN32)
    target_link_libraries(allmeyn_core PUBLIC bcrypt)  # FFmpeg's random seed on Windows
else()
    find_package(Threads REQUIRED)
    target_link_libraries(allmeyn_core PUBLIC Threads::Threads m)
endif()

# ---- Windows: the engine DLL -------------------------------------------------------------------------
if(WIN32)
    set(JDK_INCLUDE "" CACHE PATH "JDK include directory (for jni.h)")
    if(NOT EXISTS "${JDK_INCLUDE}/jni.h")
        message(FATAL_ERROR "Set -DJDK_INCLUDE=<jdk>/include (jni.h not found in '${JDK_INCLUDE}')")
    endif()
    add_library(allmeyn_desktop SHARED
        engine_jni.cpp
        audio_devices.cpp
        folder_picker.cpp
        wasapi_output.cpp
        asio_output.cpp
        endpoint_volume.cpp
        window_theme.cpp
    )
    target_include_directories(allmeyn_desktop PRIVATE
        "${JDK_INCLUDE}"
        "${CMAKE_CURRENT_SOURCE_DIR}/jni/win32"   # Windows jni_md.h (the host JDK's is for Linux)
    )
    # Steinberg's ASIO SDK headers, unmodified (GPLv3 option; see third_party/asiosdk/README.vendored).
    target_include_directories(allmeyn_desktop SYSTEM PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/third_party/asiosdk")
    target_compile_options(allmeyn_desktop PRIVATE -Wall -Wextra -Werror -O2)
    target_link_libraries(allmeyn_desktop PRIVATE allmeyn_core ole32 uuid avrt ksuser shell32 advapi32 dwmapi)
    # No lib prefix (System.load), no MinGW runtime DLLs, JNI symbols exported undecorated.
    set_target_properties(allmeyn_desktop PROPERTIES PREFIX "")
    target_link_options(allmeyn_desktop PRIVATE -static -static-libgcc -static-libstdc++ -Wl,--kill-at -s)
endif()

# ---- Linux: core tests -------------------------------------------------------------------------------
if(ALLMEYN_HOST_TESTS AND NOT WIN32)
    add_executable(desktop_core_test tests/core_test.cpp)
    target_link_libraries(desktop_core_test PRIVATE allmeyn_core)
    target_compile_options(desktop_core_test PRIVATE -Wall -Wextra -Werror)
    target_compile_definitions(desktop_core_test PRIVATE
        ALLMEYN_ASSETS="${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/androidTest/assets")
endif()
