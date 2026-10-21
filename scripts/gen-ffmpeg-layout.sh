#!/usr/bin/env bash
#
# Writes iosKit/.../ios/ffmpeg/FfmpegLayout.kt: the FFmpeg struct offsets and constants
# IosFfmpegPlayer needs, taken from the headers of the FFmpegBuild that scripts/fetch-ffmpeg.sh
# fetched. Kotlin/Native can't run cinterop for Apple targets on Linux (and then refuses to
# cross-compile the whole target), so the player binds FFmpeg through dlsym instead and reads
# its structs at these offsets. Rerun after moving FFmpegBuild to another tag.
#
# The offsets are computed with the host's C compiler (FFmpeg's structs hold only ints, int64s,
# doubles, enums and pointers, so every LP64 ABI lays them out alike) and, where clang is
# installed, checked against arm64-apple-ios with static asserts.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCES="$ROOT/iosKit/ffmpeg/FFmpegBuild/Sources"
OUT="$ROOT/iosKit/src/iosMain/kotlin/com/music/bitchord/ios/ffmpeg/FfmpegLayout.kt"
[ -d "$SOURCES" ] || { echo "error: run scripts/fetch-ffmpeg.sh first" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/include"
for name in AetherLibavformat AetherLibavcodec AetherLibavutil AetherLibswresample; do
    ln -s "$SOURCES/$name.xcframework/ios-arm64/$name.framework/Headers" "$WORK/include/$name"
done

# name|C expression, one per line: fields as offsetof, sizes, and integer constants.
cat > "$WORK/items.txt" <<'ITEMS'
FMT_PB|offsetof(AVFormatContext, pb)
FMT_NB_STREAMS|offsetof(AVFormatContext, nb_streams)
FMT_STREAMS|offsetof(AVFormatContext, streams)
FMT_DURATION|offsetof(AVFormatContext, duration)
FMT_BIT_RATE|offsetof(AVFormatContext, bit_rate)
FMT_FLAGS|offsetof(AVFormatContext, flags)
STREAM_INDEX|offsetof(AVStream, index)
STREAM_CODECPAR|offsetof(AVStream, codecpar)
STREAM_TIME_BASE|offsetof(AVStream, time_base)
STREAM_START_TIME|offsetof(AVStream, start_time)
STREAM_DURATION|offsetof(AVStream, duration)
PAR_CODEC_TYPE|offsetof(AVCodecParameters, codec_type)
PAR_CODEC_ID|offsetof(AVCodecParameters, codec_id)
PAR_FORMAT|offsetof(AVCodecParameters, format)
PAR_BIT_RATE|offsetof(AVCodecParameters, bit_rate)
PAR_BITS_PER_CODED_SAMPLE|offsetof(AVCodecParameters, bits_per_coded_sample)
PAR_BITS_PER_RAW_SAMPLE|offsetof(AVCodecParameters, bits_per_raw_sample)
PAR_SAMPLE_RATE|offsetof(AVCodecParameters, sample_rate)
PAR_CH_LAYOUT|offsetof(AVCodecParameters, ch_layout)
CODEC_PKT_TIMEBASE|offsetof(AVCodecContext, pkt_timebase)
LAYOUT_SIZE|sizeof(AVChannelLayout)
LAYOUT_ORDER|offsetof(AVChannelLayout, order)
LAYOUT_NB_CHANNELS|offsetof(AVChannelLayout, nb_channels)
FRAME_EXTENDED_DATA|offsetof(AVFrame, extended_data)
FRAME_NB_SAMPLES|offsetof(AVFrame, nb_samples)
FRAME_FORMAT|offsetof(AVFrame, format)
FRAME_PTS|offsetof(AVFrame, pts)
FRAME_BEST_EFFORT_TIMESTAMP|offsetof(AVFrame, best_effort_timestamp)
FRAME_SAMPLE_RATE|offsetof(AVFrame, sample_rate)
FRAME_CH_LAYOUT|offsetof(AVFrame, ch_layout)
PACKET_PTS|offsetof(AVPacket, pts)
PACKET_DTS|offsetof(AVPacket, dts)
PACKET_STREAM_INDEX|offsetof(AVPacket, stream_index)
IO_BUFFER|offsetof(AVIOContext, buffer)
IO_SEEKABLE|offsetof(AVIOContext, seekable)
RATIONAL_SIZE|sizeof(AVRational)
MEDIA_TYPE_AUDIO|AVMEDIA_TYPE_AUDIO
SAMPLE_FMT_FLTP|AV_SAMPLE_FMT_FLTP
CHANNEL_ORDER_UNSPEC|AV_CHANNEL_ORDER_UNSPEC
SEEK_SIZE|AVSEEK_SIZE
SEEK_FORCE|AVSEEK_FORCE
SEEK_FLAG_BACKWARD|AVSEEK_FLAG_BACKWARD
FMT_FLAG_CUSTOM_IO|AVFMT_FLAG_CUSTOM_IO
IO_SEEKABLE_NORMAL|AVIO_SEEKABLE_NORMAL
ERROR_EOF|AVERROR_EOF
ERROR_EXIT|AVERROR_EXIT
ERROR_INVALIDDATA|AVERROR_INVALIDDATA
TIME_BASE|AV_TIME_BASE
LOG_ERROR|AV_LOG_ERROR
AVFORMAT_MAJOR|LIBAVFORMAT_VERSION_MAJOR
AVCODEC_MAJOR|LIBAVCODEC_VERSION_MAJOR
AVUTIL_MAJOR|LIBAVUTIL_VERSION_MAJOR
SWRESAMPLE_MAJOR|LIBSWRESAMPLE_VERSION_MAJOR
ITEMS

{
    echo '#include <stdio.h>'
    echo '#include <stddef.h>'
    echo '#include "AetherLibavformat/avformat.h"'
    echo '#include "AetherLibavformat/avio.h"'
    echo '#include "AetherLibavcodec/avcodec.h"'
    echo '#include "AetherLibswresample/swresample.h"'
    echo '#include "AetherLibavutil/channel_layout.h"'
    echo '#include "AetherLibavutil/error.h"'
    echo '#include "AetherLibavutil/samplefmt.h"'
} > "$WORK/prelude.h"

{
    echo '#include "prelude.h"'
    echo 'int main(void) {'
    while IFS='|' read -r name expr; do
        echo "    printf(\"$name %lld\\n\", (long long)($expr));"
    done < "$WORK/items.txt"
    echo '    return 0;'
    echo '}'
} > "$WORK/layout.c"

CC="${CC:-cc}"
"$CC" -std=c11 -w -I"$WORK/include" -I"$WORK" -o "$WORK/layout" "$WORK/layout.c"
"$WORK/layout" > "$WORK/values.txt"

# The same values must hold for the iPhone: asserted there by clang, with stand-ins for the few
# libc headers FFmpeg's include (only their types matter to the layouts).
if command -v clang >/dev/null 2>&1; then
    mkdir -p "$WORK/sysroot"
    for h in stdio.h stdlib.h string.h math.h errno.h time.h pthread.h sys/time.h sys/types.h; do
        mkdir -p "$WORK/sysroot/$(dirname "$h")"
        : > "$WORK/sysroot/$h"
    done
    cat > "$WORK/sysroot/stdio.h" <<'H'
typedef struct __sFILE FILE;
#define SEEK_SET 0
#define SEEK_CUR 1
#define SEEK_END 2
H
    cat > "$WORK/sysroot/errno.h" <<'H'
#define EAGAIN 35
#define EINVAL 22
#define ENOMEM 12
#define ENOSYS 78
#define EPERM 1
#define ENOENT 2
#define EIO 5
#define EDOM 33
#define ERANGE 34
H
    cat > "$WORK/sysroot/inttypes.h" <<'H'
#include <stdint.h>
#define PRId64 "lld"
#define PRIu64 "llu"
#define PRIx64 "llx"
#define PRIX64 "llX"
#define PRId32 "d"
#define PRIu32 "u"
#define PRIx32 "x"
#define PRIX32 "X"
H
    cat > "$WORK/sysroot/time.h" <<'H'
typedef long time_t;
struct tm { int tm_sec; };
H
    {
        echo '#include "prelude.h"'
        while read -r name value; do
            expr="$(grep "^$name|" "$WORK/items.txt" | cut -d'|' -f2-)"
            echo "_Static_assert((long long)($expr) == ${value}LL, \"$name\");"
        done < "$WORK/values.txt"
    } > "$WORK/check.c"
    clang --target=arm64-apple-ios16.0 -std=c11 -ffreestanding -nostdlibinc -fsyntax-only -w \
        -isystem "$WORK/sysroot" -I"$WORK/include" -I"$WORK" "$WORK/check.c"
    echo "layout checked for arm64-apple-ios"
else
    echo "warning: clang not found; layout not checked for arm64-apple-ios" >&2
fi

TAG="$(git -C "$ROOT/iosKit/ffmpeg/FFmpegBuild" describe --tags --exact-match HEAD 2>/dev/null || echo unknown)"
{
    echo '// Generated by scripts/gen-ffmpeg-layout.sh from FFmpegBuild '"$TAG"'; do not edit.'
    echo 'package com.music.bitchord.ios.ffmpeg'
    echo
    echo '/** Offsets into FFmpeg'"'"'s structs, and its constants, for the FFmpegBuild the app embeds. */'
    echo 'internal object FfmpegLayout {'
    echo "    const val BUILD_TAG = \"$TAG\""
    while read -r name value; do
        if [ "$name" = "SEEK_FORCE" ] || [ "$name" = "SEEK_SIZE" ] || [ "$name" = "TIME_BASE" ]; then
            echo "    const val $name = ${value}L"
        else
            echo "    const val $name = $value"
        fi
    done < "$WORK/values.txt"
    echo '    /** `AVERROR(EAGAIN)`: errno values are Darwin'"'"'s on the phone, whatever host ran this script. */'
    echo '    const val ERROR_EAGAIN = -35'
    echo '    const val NOPTS_VALUE = Long.MIN_VALUE'
    echo '}'
} > "$OUT"
echo "wrote $OUT"
