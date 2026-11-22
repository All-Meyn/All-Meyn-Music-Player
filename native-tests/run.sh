#!/usr/bin/env bash
# Host-side tests for the native audio code (ring buffer, PCM conversion, DSD decoding, DSP).
# Needs gcc and g++. Run from anywhere: native-tests/run.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CPP="$HERE/../app/src/main/cpp"
OUT=/tmp/allmeyn-native-tests
mkdir -p "$OUT"
SAN="-fsanitize=address,undefined -fno-omit-frame-pointer"

echo "== ring buffer + PCM conversion"
gcc -std=gnu11 -O1 -Wall -Wextra -Werror $SAN -I"$CPP" "$CPP/usb_audio_core.c" "$HERE/core_test.c" -lm -o "$OUT/core"
"$OUT/core"

echo "== ring buffer threads (ThreadSanitizer)"
gcc -std=gnu11 -O1 -g -fsanitize=thread -I"$CPP" "$CPP/usb_audio_core.c" "$HERE/ring_tsan_test.c" -lm -lpthread -o "$OUT/tsan"
"$OUT/tsan"

echo "== DSD (DSF/DFF): PCM conversion, DoP, seeking"
gcc -std=gnu11 -O2 -Wall -Wextra $SAN -I"$CPP/decoder" "$CPP/decoder/dsd.c" "$HERE/dsd_test.c" -lm -o "$OUT/dsd"
"$OUT/dsd"
echo "== DSP chain: compressor/expander, bass, impact + vocal restoration"
g++ -std=c++17 -O2 -Wall -Wextra -Werror $SAN -I"$CPP/dsp" "$CPP/dsp/dsp_chain.cpp" "$HERE/dsp_test.cpp" -lm -o "$OUT/dsp"
"$OUT/dsp"
echo "All native tests passed"
