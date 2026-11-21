#!/usr/bin/env bash
# Builds a minimal static FFmpeg (audio decoding only, LGPL) for the app's Android ABIs: APE and
# WavPack for our own decoder, and the codecs Media3's FFmpeg decoder (libffmpegJNI) handles.
#
#   scripts/build-ffmpeg.sh <android-ndk-dir> [ffmpeg-source-dir]
#
# Output: app/src/main/cpp/ffmpeg/<abi>/{include,lib}. CMake links it when present; without it the
# app still builds, but APE/WavPack files won't play. CI runs this (cached) before Gradle.
set -euo pipefail

NDK="${1:?usage: build-ffmpeg.sh <android-ndk-dir> [ffmpeg-source-dir]}"
FFMPEG_TAG="n7.1.5"  # latest 7.1 maintenance release (security fixes); bump with each point release
API=26
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_ROOT="$ROOT/app/src/main/cpp/ffmpeg"
SRC="${2:-$ROOT/build/ffmpeg-src}"

if [ ! -d "$SRC" ]; then
  git clone --depth 1 --branch "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg "$SRC"
fi

HOST_TAG="linux-x86_64"
case "$(uname -s)" in Darwin) HOST_TAG="darwin-x86_64" ;; esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

# Everything off, then only what decoding needs. No GPL components (no --enable-gpl).
#  - demuxers ape,wv + decoders ape,wavpack: our APE/WavPack decoder (decoder/ffdec.c)
#  - the other decoders + swresample: Media3's FFmpeg audio decoder (media3_ffmpeg/), the same
#    codec list as the Jellyfin build it replaces (Media3's own extractors feed it packets)
MEDIA3_DECODERS=flac,alac,pcm_mulaw,pcm_alaw,mp3,aac,ac3,eac3,dca,mlp,truehd
COMMON_FLAGS=(
  --disable-everything --disable-programs --disable-doc --disable-network --disable-autodetect
  --disable-avdevice --disable-swscale --disable-avfilter --disable-postproc --disable-symver
  --enable-avformat --enable-avcodec --enable-avutil --enable-swresample
  --enable-demuxer=ape,wv --enable-decoder=ape,wavpack,$MEDIA3_DECODERS
  --enable-pic --enable-static --disable-shared
)

build_abi() {
  local abi="$1" arch="$2" cpu="$3" cc_prefix="$4"; shift 4
  local out="$OUT_ROOT/$abi"
  local work="$ROOT/build/ffmpeg-$abi"
  echo "=== FFmpeg $FFMPEG_TAG for $abi ==="
  rm -rf "$work" && mkdir -p "$work"
  (
    cd "$work"
    "$SRC/configure" --prefix="$out" \
      --target-os=android --arch="$arch" --cpu="$cpu" --enable-cross-compile \
      --cc="$TC/bin/${cc_prefix}${API}-clang" --cxx="$TC/bin/${cc_prefix}${API}-clang++" \
      --ar="$TC/bin/llvm-ar" --ranlib="$TC/bin/llvm-ranlib" --nm="$TC/bin/llvm-nm" \
      --strip="$TC/bin/llvm-strip" --sysroot="$TC/sysroot" \
      "${COMMON_FLAGS[@]}" "$@"
    make -j"$JOBS"
    make install
  )
}

build_abi arm64-v8a   aarch64 armv8-a aarch64-linux-android
build_abi armeabi-v7a arm     armv7-a armv7a-linux-androideabi --disable-asm
build_abi x86_64      x86_64  x86-64  x86_64-linux-android --disable-asm
echo "FFmpeg installed to $OUT_ROOT"
