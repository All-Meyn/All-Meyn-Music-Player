# All-Meyn Player for Windows

Compose Desktop app (Kotlin) with a native audio engine (`native/`, C++), sharing the Android
app's C/C++ audio code and logo. Built by `.github/workflows/desktop.yml`, which uploads the MSI
installer as the `all-meyn-player-windows` artifact.

Build locally (Linux, cross-compiling the engine; needs `g++-mingw-w64-x86-64-posix`, CMake, a JDK):

    scripts/build-ffmpeg-desktop.sh windows
    cmake -S desktop/native -B build/desktop-native -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_TOOLCHAIN_FILE=$PWD/desktop/native/cmake/mingw-w64.cmake -DJDK_INCLUDE=$JAVA_HOME/include
    cmake --build build/desktop-native
    cp build/desktop-native/allmeyn_desktop.dll desktop/resources/windows-x64/

Core tests on Linux: `scripts/build-ffmpeg-desktop.sh linux`, then
`cmake -S desktop/native -B build/desktop-tests -DALLMEYN_HOST_TESTS=ON && cmake --build build/desktop-tests && build/desktop-tests/desktop_core_test`.

Then on Windows: `gradlew -p desktop run` (or `test`, `packageMsi`).

Stages: W1 skeleton (this) → W2 playback → W3 library → W4 bit-perfect output (WASAPI exclusive,
DoP, ASIO native DSD).
