#!/usr/bin/env bash
#
# Puts Automix's two model weights where the iOS app bundles them (iosApp/iosApp/Models,
# git-ignored), under the names Android unpacks them by, each checked against its SHA-256:
#   beat_this_final0_q8.onnx  Beat This! (CPJKU, MIT), the export upstream BitChord v1.9 plans with,
#                             fetched from upstream at a pinned commit;
#   vocals_umxhq_int8.onnx    open-unmix's vocals target (Inria/SigSep, MIT), already in this
#                             repository's Android assets.
# Idempotent. Runs on macOS and Linux.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/iosApp/iosApp/Models"
UPSTREAM="https://raw.githubusercontent.com/kushagrasinghx/BitChord/b690dc146009d4815825fa4a20815ef2e4ee8898/app/src/main/assets"

check() { # file sha256
    local sum
    sum="$( (shasum -a 256 "$1" 2>/dev/null || sha256sum "$1") | cut -d' ' -f1)"
    [ "$sum" = "$2" ]
}

fetch() { # name sha256 local-copy
    local name="$1" sha="$2" local_copy="$3"
    if [ -f "$DEST/$name" ] && check "$DEST/$name" "$sha"; then
        echo "$name already at $DEST"
        return
    fi
    if [ -n "$local_copy" ] && [ -f "$local_copy" ] && check "$local_copy" "$sha"; then
        cp "$local_copy" "$DEST/$name"
    else
        curl -fsSL --retry 4 -o "$DEST/$name.part" "$UPSTREAM/$name"
        check "$DEST/$name.part" "$sha" || { rm -f "$DEST/$name.part"; echo "error: $name does not match its pinned SHA-256" >&2; exit 1; }
        mv "$DEST/$name.part" "$DEST/$name"
    fi
    echo "$name at $DEST"
}

mkdir -p "$DEST"
fetch beat_this_final0_q8.onnx 7801f9c1adfd5ff193f37ececa3320bf1fda1ddee2bf7de341cea4c25ffd0acd ""
fetch vocals_umxhq_int8.onnx a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb "$ROOT/app/src/main/assets/vocals_umxhq_int8.onnx"
