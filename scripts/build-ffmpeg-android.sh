#!/usr/bin/env bash
# Build a minimal static ffmpeg + ffprobe for Android arm64 (API 24).
#
# Pinned source: FFmpeg n7.1.1. LGPL-only configuration (no --enable-gpl,
# no non-free components), so it can ship in a closed-source app.
#
# Inputs (env):
#   NDK_DIR     - path to an unpacked Android NDK (r27c)
#   FFMPEG_SRC  - path to the unpacked FFmpeg source tree
# Outputs:
#   $FFMPEG_SRC/ffmpeg, $FFMPEG_SRC/ffprobe  (static, self-contained)
#
# The two programs link only against Android system libraries, so each is a
# single self-contained executable. They are meant to be placed in the APK as
# jniLibs/arm64-v8a/libffmpeg.so and libffprobe.so.
set -euo pipefail

NDK_DIR=${NDK_DIR:?set NDK_DIR}
FFMPEG_SRC=${FFMPEG_SRC:?set FFMPEG_SRC}
API=${API:-24}

TC="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TC/bin/aarch64-linux-android${API}-clang"

cd "$FFMPEG_SRC"

./configure \
  --target-os=android --arch=aarch64 --cpu=armv8-a \
  --enable-cross-compile \
  --cc="$CC" \
  --cxx="$TC/bin/aarch64-linux-android${API}-clang++" \
  --ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm" \
  --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" \
  --sysroot="$TC/sysroot" \
  --enable-static --disable-shared --enable-small \
  --disable-doc --disable-debug --disable-programs \
  --disable-avdevice --disable-postproc --disable-network \
  --enable-ffmpeg --enable-ffprobe --disable-ffplay \
  --disable-everything \
  --enable-demuxer=mov,matroska,mp3,ogg,aac,mpegts,hls,image2,mjpeg,wav \
  --enable-muxer=mp4,mov,matroska,webm,ipod,mp3,adts,ogg,opus,image2,mjpeg,wav \
  --enable-parser=aac,h264,hevc,vp9,av1,opus,vorbis,mpeg4video,mjpeg,flac \
  --enable-decoder=aac,h264,hevc,vp9,av1,mp3,opus,vorbis,flac,mjpeg,pcm_s16le \
  --enable-encoder=aac,mjpeg,pcm_s16le \
  --enable-bsf=aac_adtstoasc,h264_mp4toannexb,hevc_mp4toannexb,extract_extradata,vp9_superframe \
  --enable-protocol=file,pipe,data,concat,crypto \
  --enable-swresample --enable-swscale \
  --extra-cflags="-Os"

make -j"$(nproc)"
"$TC/bin/llvm-strip" ffmpeg ffprobe
ls -la ffmpeg ffprobe
