#!/usr/bin/env bash
# Installs Termux's official bootstrap as the APK runtime asset.
#
# The bootstrap (and every package later installed with `pkg`) is built for
# /data/data/com.termux; libpuppy_remap.so maps that onto Puppy's data dir
# at run time, so official binaries and the official repository work as is.
# The release is pinned and checked against GitHub's published sha256.
#
# Usage: runtime/fetch-termux-bootstrap.sh
set -euo pipefail

TAG="bootstrap-2026.10.11-r1+apt.android-7"
SHA256="b7827bc99710293d0e871df8b96d9d3a8ab6b22ee4b4a6f3ac076754818990ab"
ARCH=aarch64
ABI=arm64-v8a

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ASSETS="$ROOT/app/src/main/assets/runtime"
mkdir -p "$ASSETS"
ZIP="$ASSETS/bootstrap-$ABI.zip"

curl -fL --retry 3 -o "$ZIP.part" \
	"https://github.com/termux/termux-packages/releases/download/${TAG//+/%2B}/bootstrap-$ARCH.zip"
got=$( (command -v sha256sum >/dev/null && sha256sum "$ZIP.part" || shasum -a 256 "$ZIP.part") | cut -d' ' -f1)
if [ "$got" != "$SHA256" ]; then
	rm -f "$ZIP.part"
	echo "checksum mismatch: $got" >&2
	exit 1
fi
mv "$ZIP.part" "$ZIP"
cat > "$ASSETS/bootstrap-$ABI.json" <<JSON
{
  "prefix": "/data/data/com.termux/files/usr",
  "remapFrom": "/data/data/com.termux",
  "abi": "$ABI",
  "sha256": "$SHA256",
  "revision": "termux $TAG"
}
JSON
echo "Installed $ZIP"
