#!/usr/bin/env bash
#
# Fetches the prebuilt FFmpeg the iOS app decodes Opus/Vorbis/WebM/Ogg/DASH with: FFmpegBuild
# (https://github.com/superuser404notfound/FFmpegBuild, LGPL, frameworks prefixed `Aether`), pinned
# to one tag. It lands in iosKit/ffmpeg/FFmpegBuild (git-ignored); the xcframeworks are then at
# iosKit/ffmpeg/FFmpegBuild/Sources/*.xcframework, where iosKit's cinterop and the Xcode project
# look for them. Idempotent: a clone already at the tag is left alone. Runs on macOS and Linux.
set -euo pipefail

TAG="${FFMPEG_BUILD_TAG:-3.9.0}"
REPO="${FFMPEG_BUILD_REPO:-https://github.com/superuser404notfound/FFmpegBuild.git}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/iosKit/ffmpeg/FFmpegBuild"
# The frameworks the app needs: the four it calls, and the two libavcodec links against.
NEEDED=(AetherLibavcodec AetherLibavformat AetherLibavutil AetherLibswresample AetherLibdav1d AetherLibzvbi)

complete() {
    for name in "${NEEDED[@]}"; do
        [ -d "$DEST/Sources/$name.xcframework/ios-arm64/$name.framework/Headers" ] || return 1
        [ -d "$DEST/Sources/$name.xcframework/ios-arm64_x86_64-simulator/$name.framework" ] || return 1
    done
}

if [ -d "$DEST/.git" ]; then
    have="$(git -C "$DEST" describe --tags --exact-match HEAD 2>/dev/null || true)"
    if [ "$have" = "$TAG" ] && complete; then
        echo "FFmpegBuild $TAG already at $DEST"
        exit 0
    fi
    echo "FFmpegBuild at $DEST is '${have:-unknown}', not $TAG (or incomplete): fetching again"
fi

rm -rf "$DEST.tmp"
mkdir -p "$(dirname "$DEST")"
git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$TAG" "$REPO" "$DEST.tmp"
if ! (DEST="$DEST.tmp" complete); then
    # A checkout without the binaries (say, LFS pointers) would only fail later, less clearly.
    echo "error: FFmpegBuild $TAG does not hold the expected xcframeworks" >&2
    rm -rf "$DEST.tmp"
    exit 1
fi
rm -rf "$DEST"
mv "$DEST.tmp" "$DEST"
echo "FFmpegBuild $TAG fetched to $DEST"
