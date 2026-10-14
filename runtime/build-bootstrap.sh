#!/usr/bin/env bash
# Builds the local runtime bootstrap for Puppy's own PREFIX
# (/data/data/org.pocketlab.puppy/files/usr) from pinned termux-packages
# recipes, and installs it as an APK asset with its manifest.
#
# Needs Docker. Building every bootstrap package from source takes hours.
# Usage: runtime/build-bootstrap.sh [aarch64|x86_64]
set -euo pipefail

TERMUX_PACKAGES_REPO=https://github.com/termux/termux-packages
TERMUX_PACKAGES_COMMIT=4c004cd229267282586e911c2d8f43916b16a6bf
APP_ID=org.pocketlab.puppy
ARCH=${1:-aarch64}

case "$ARCH" in
	aarch64) ABI=arm64-v8a ;;
	x86_64) ABI=x86_64 ;;
	*) echo "unsupported architecture: $ARCH" >&2; exit 1 ;;
esac

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK="$ROOT/runtime/build/termux-packages"
ASSETS="$ROOT/app/src/main/assets/runtime"

if [ ! -d "$WORK/.git" ]; then
	git init -q "$WORK"
	git -C "$WORK" remote add origin "$TERMUX_PACKAGES_REPO"
fi
git -C "$WORK" fetch -q --depth 1 origin "$TERMUX_PACKAGES_COMMIT"
git -C "$WORK" checkout -q --force FETCH_HEAD
git -C "$WORK" clean -qfdx -e output

# Rebuild for our package name; never reuse binaries built for com.termux.
for patch in "$ROOT"/runtime/patches/*.patch; do
	[ -e "$patch" ] || continue
	git -C "$WORK" apply "$patch"
done
# TERMUX_REPO_APP__PACKAGE_NAME stays com.termux on purpose: because it then
# differs from our package name, build-package.sh refuses to download
# dependencies from the official repo and compiles everything locally.
sed -i.orig \
	-e "s|^TERMUX_APP__PACKAGE_NAME=\"com.termux\"|TERMUX_APP__PACKAGE_NAME=\"$APP_ID\"|" \
	"$WORK/scripts/properties.sh"
grep -q "TERMUX_APP__PACKAGE_NAME=\"$APP_ID\"" "$WORK/scripts/properties.sh"
grep -q '^TERMUX_REPO_APP__PACKAGE_NAME="com.termux"' "$WORK/scripts/properties.sh"

# The builder image is amd64-only; Docker Desktop emulates it on Apple Silicon.
EMULATED=false
if [ "$(uname -m)" = arm64 ] || [ "$(uname -m)" = aarch64 ]; then
	EMULATED=true
	export TERMUX_DOCKER_RUN_EXTRA_ARGS="--platform linux/amd64 ${TERMUX_DOCKER_RUN_EXTRA_ARGS:-}"
fi

# run-docker.sh only grants fuse (needed by the NDK toolchain's
# fuse-overlayfs) on Linux hosts; Docker Desktop on macOS needs it too.
if [ "$(uname)" = Darwin ]; then
	export TERMUX_DOCKER_RUN_EXTRA_ARGS="--cap-add SYS_ADMIN --device /dev/fuse --security-opt seccomp=$WORK/scripts/profile.json ${TERMUX_DOCKER_RUN_EXTRA_ARGS:-}"
fi

# Start (or reuse) the builder container.
(cd "$WORK" && ./scripts/run-docker.sh true)

if $EMULATED; then
	# Docker Desktop's amd64 emulation has no openat2(2), and the image's
	# security-patched GNU tar (1.35+dfsg-4ubuntu0.4) fails every nested
	# extraction with ENOSYS. Use the same series' base build in the
	# throwaway container; the archives it unpacks are checksummed by the
	# package recipes.
	TAR_DEB=tar_1.35+dfsg-4_amd64.deb
	TAR_SHA256=4e066f8382f775d6600eee715add8fdc8da5ea8f0cf7b0f715aba38c8d57d64f
	docker exec termux-package-builder sh -euc "
		if ! dpkg -s tar | grep -q '^Version: 1.35+dfsg-4\$'; then
			cd /tmp
			curl -fsSLO https://archive.ubuntu.com/ubuntu/pool/main/t/tar/$TAR_DEB
			echo '$TAR_SHA256  $TAR_DEB' | sha256sum -c -
			sudo dpkg -i $TAR_DEB
		fi"
fi

(cd "$WORK" && ./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures "$ARCH")

ZIP="$WORK/bootstrap-$ARCH.zip"
[ -f "$ZIP" ] || { echo "bootstrap was not produced: $ZIP" >&2; exit 1; }

mkdir -p "$ASSETS"
cp "$ZIP" "$ASSETS/bootstrap-$ABI.zip"
SHA=$(shasum -a 256 "$ASSETS/bootstrap-$ABI.zip" | cut -d' ' -f1)
cat > "$ASSETS/bootstrap-$ABI.json" <<JSON
{
  "prefix": "/data/data/$APP_ID/files/usr",
  "abi": "$ABI",
  "sha256": "$SHA",
  "revision": "termux-packages@${TERMUX_PACKAGES_COMMIT:0:12}",
  "execMode": "direct-exec-target-28"
}
JSON
# No Puppy package repository exists yet: never point apt at com.termux's.
python3 "$ROOT/runtime/finalize-bootstrap.py" "$ASSETS/bootstrap-$ABI.zip" "$ASSETS/bootstrap-$ABI.json"
echo "Installed $ASSETS/bootstrap-$ABI.zip"
