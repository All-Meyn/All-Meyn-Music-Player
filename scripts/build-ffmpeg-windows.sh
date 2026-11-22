#!/usr/bin/env bash
# Builds a minimal static FFmpeg (audio decoding only, LGPL) for the Windows desktop app, cross-
# compiled with MinGW-w64 (Ubuntu: g++-mingw-w64-x86-64-posix). Unlike Android, where Media3 reads
# the files, the desktop engine uses FFmpeg's demuxers too.
#
#   scripts/build-ffmpeg-windows.sh [ffmpeg-source-dir]
#
# Output: desktop/native/ffmpeg/windows-x64/{include,lib}. CI runs this (cached) before building
# the native engine (desktop/native).
set -euo pipefail

FFMPEG_TAG="n7.1.5"  # keep in step with scripts/build-ffmpeg.sh (Android)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/desktop/native/ffmpeg/windows-x64"
SRC="${1:-$ROOT/build/ffmpeg-src}"
CROSS=x86_64-w64-mingw32-

if [ ! -d "$SRC" ]; then
  git clone --depth 1 --branch "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg "$SRC"
fi
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

# Everything off, then only what playing music files needs. No GPL components (no --enable-gpl).
DEMUXERS=flac,mov,mp3,ogg,wav,w64,aiff,ape,wv,aac
DECODERS=flac,alac,mp3,mp3float,aac,vorbis,opus,ape,wavpack,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be,pcm_f64le,pcm_u8,pcm_mulaw,pcm_alaw
PARSERS=flac,mpegaudio,aac,vorbis,opus

WORK="$ROOT/build/ffmpeg-windows-x64"
rm -rf "$WORK" && mkdir -p "$WORK"
(
  cd "$WORK"
  "$SRC/configure" --prefix="$OUT" \
    --target-os=mingw32 --arch=x86_64 --enable-cross-compile --cross-prefix="$CROSS" \
    --disable-everything --disable-programs --disable-doc --disable-network --disable-autodetect \
    --disable-avdevice --disable-swscale --disable-avfilter --disable-postproc --disable-x86asm \
    --enable-avformat --enable-avcodec --enable-avutil --enable-swresample \
    --enable-protocol=file --enable-demuxer=$DEMUXERS --enable-decoder=$DECODERS --enable-parser=$PARSERS \
    --enable-static --disable-shared --extra-cflags=-O2
  make -j"$JOBS"
  make install
)
echo "FFmpeg installed to $OUT"
