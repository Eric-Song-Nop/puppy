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

# Termux's builder image, run in a persistent container. Sources are copied
# in instead of bind-mounted: Docker Desktop's shared folders refuse the
# chown that scripts/run-docker.sh performs, and copying is faster anyway.
# The container keeps ~/.termux-build, so rebuilds are incremental.
IMAGE=ghcr.io/termux/package-builder
CONTAINER=puppy-bootstrap-builder
RUN_ARGS=(--cap-add SYS_ADMIN --device /dev/fuse --security-opt "seccomp=$WORK/scripts/profile.json")

EMULATED=false
if [ "$(uname -m)" = arm64 ] || [ "$(uname -m)" = aarch64 ]; then
	# The image is amd64-only; Docker Desktop emulates it on Apple Silicon.
	EMULATED=true
	RUN_ARGS+=(--platform linux/amd64)
fi

if ! docker container inspect "$CONTAINER" >/dev/null 2>&1; then
	docker run --detach --init --tty --name "$CONTAINER" "${RUN_ARGS[@]}" "$IMAGE" >/dev/null
	# Some packages misbehave with pids above 65535 (as run-docker.sh notes).
	docker run --rm --privileged --pid="container:$CONTAINER" "${RUN_ARGS[@]}" "$IMAGE" \
		sh -c 'echo 65535 | sudo tee /proc/sys/kernel/pid_max >/dev/null' || :
fi
docker start "$CONTAINER" >/dev/null

if $EMULATED; then
	# Docker Desktop's amd64 emulation has no openat2(2), and the image's
	# security-patched GNU tar (1.35+dfsg-4ubuntu0.4) fails every nested
	# extraction with ENOSYS. Use the same series' base build in the
	# throwaway container; the archives it unpacks are checksummed by the
	# package recipes.
	TAR_DEB=tar_1.35+dfsg-4_amd64.deb
	TAR_SHA256=4e066f8382f775d6600eee715add8fdc8da5ea8f0cf7b0f715aba38c8d57d64f
	docker exec "$CONTAINER" sh -euc "
		if ! dpkg -s tar | grep -q '^Version: 1.35+dfsg-4\$'; then
			cd /tmp
			curl -fsSLO https://archive.ubuntu.com/ubuntu/pool/main/t/tar/$TAR_DEB
			echo '$TAR_SHA256  $TAR_DEB' | sha256sum -c -
			sudo dpkg -i $TAR_DEB
		fi"
fi

SRC=/home/builder/termux-packages
# PUPPY_BOOTSTRAP_FORCE=1 forgets what was built (upstream's -f is unsafe:
# with its variables unset it runs `rm -f /*`).
if [ -n "${PUPPY_BOOTSTRAP_FORCE:-}" ]; then
	docker exec "$CONTAINER" sh -c "rm -rf /data/data/.built-packages /home/builder/.termux-build/.built-packages $SRC/output"
fi

# Keep output/: the built .debs live there, and termux-packages' "already
# built" markers inside the container would otherwise skip rebuilding them.
docker exec "$CONTAINER" sh -c "mkdir -p $SRC && find $SRC -mindepth 1 -maxdepth 1 ! -name output -exec rm -rf {} +"
tar -C "$WORK" --exclude=./.git --exclude=./output -cf - . | docker exec -i "$CONTAINER" tar -C "$SRC" -xf -
# No package repository exists yet, so no apt: the default set's apt and
# its network stack (gnutls -> unbound -> Python -> X11) buy nothing.
PACKAGES="bash coreutils dash diffutils ed findutils gawk grep gzip less nano patch procps psmisc sed tar termux-core termux-exec unzip util-linux"
docker exec -e PUPPY_BOOTSTRAP_PACKAGES="${PUPPY_BOOTSTRAP_PACKAGES:-$PACKAGES}" -w "$SRC" "$CONTAINER" \
	./scripts/build-bootstraps.sh --architectures "$ARCH"
docker cp "$CONTAINER:$SRC/bootstrap-$ARCH.zip" "$WORK/bootstrap-$ARCH.zip"
# A bootstrap without its packages builds "successfully"; refuse to ship it.
python3 -c 'import sys, zipfile; n = zipfile.ZipFile(sys.argv[1]).namelist(); sys.exit(0 if "bin/bash" in n and len(n) > 200 else "bootstrap is missing its packages (no bin/bash)")' "$WORK/bootstrap-$ARCH.zip"

ZIP="$WORK/bootstrap-$ARCH.zip"
[ -f "$ZIP" ] || { echo "bootstrap was not produced: $ZIP" >&2; exit 1; }

mkdir -p "$ASSETS"
cp "$ZIP" "$ASSETS/bootstrap-$ABI.zip"
SHA=$( (command -v sha256sum >/dev/null && sha256sum "$ASSETS/bootstrap-$ABI.zip" || shasum -a 256 "$ASSETS/bootstrap-$ABI.zip") | cut -d' ' -f1)
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
