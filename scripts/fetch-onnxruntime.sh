#!/usr/bin/env bash
#
# Fetches Microsoft's prebuilt ONNX Runtime for iOS, which BitChordModels.framework (Automix's beat
# and vocal models, native/models/ios) links statically: the `onnxruntime-c` CocoaPods archive,
# pinned to one version and checked against its SHA-256. It lands in iosKit/onnxruntime
# (git-ignored): onnxruntime.xcframework for Xcode, and include/onnxruntime/*.h for compiling the
# bridge anywhere else (CI's Linux check). Idempotent. Runs on macOS and Linux.
set -euo pipefail

VERSION="${ORT_VERSION:-1.22.0}"
SHA256="${ORT_SHA256:-90d9de5a139087a6b05a18125d01d01d198820e1731e6f0f11b38749b2ab181f}"
URL="https://download.onnxruntime.ai/pod-archive-onnxruntime-c-$VERSION.zip"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/iosKit/onnxruntime"

if [ -f "$DEST/VERSION" ] && [ "$(cat "$DEST/VERSION")" = "$VERSION" ] \
    && [ -f "$DEST/onnxruntime.xcframework/ios-arm64/onnxruntime.framework/onnxruntime" ] \
    && [ -f "$DEST/include/onnxruntime/onnxruntime_c_api.h" ]; then
    echo "ONNX Runtime $VERSION already at $DEST"
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl -fsSL --retry 4 -o "$work/ort.zip" "$URL"
echo "$SHA256  $work/ort.zip" | shasum -a 256 -c - >/dev/null 2>&1 \
    || echo "$SHA256  $work/ort.zip" | sha256sum -c - >/dev/null 2>&1 \
    || { echo "error: $URL does not match its pinned SHA-256" >&2; exit 1; }
unzip -q "$work/ort.zip" -d "$work/x"
rm -rf "$DEST"
mkdir -p "$DEST/include"
mv "$work/x/onnxruntime.xcframework" "$DEST/"
# Only the device and simulator slices: the macOS one is a third of the download and unused.
rm -rf "$DEST/onnxruntime.xcframework/macos-arm64_x86_64"
/usr/bin/env python3 - "$DEST/onnxruntime.xcframework/Info.plist" <<'PY'
import plistlib, sys
path = sys.argv[1]
with open(path, "rb") as f:
    info = plistlib.load(f)
info["AvailableLibraries"] = [l for l in info["AvailableLibraries"] if l.get("SupportedPlatform") != "macos"]
with open(path, "wb") as f:
    plistlib.dump(info, f)
PY
mv "$work/x/Headers" "$DEST/include/onnxruntime"
cp "$work/x/LICENSE" "$DEST/LICENSE"
echo "$VERSION" > "$DEST/VERSION"
echo "ONNX Runtime $VERSION at $DEST"
