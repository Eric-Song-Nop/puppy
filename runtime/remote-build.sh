#!/usr/bin/env bash
# Builds the bootstrap on a remote x86_64 Linux host with Docker (native
# speed; Apple Silicon has to emulate the amd64 builder image) and copies
# the result into app/src/main/assets/runtime/ for local APK builds.
#
# The remote checkout under ~/puppy-runtime keeps termux-packages' build
# cache, so later builds only recompile what changed.
#
# Usage: runtime/remote-build.sh <ssh-host> [aarch64|x86_64]
set -euo pipefail

HOST=${1:?usage: remote-build.sh <ssh-host> [arch]}
ARCH=${2:-aarch64}
case "$ARCH" in
	aarch64) ABI=arm64-v8a ;;
	x86_64) ABI=x86_64 ;;
	*) echo "unsupported architecture: $ARCH" >&2; exit 1 ;;
esac

ROOT=$(cd "$(dirname "$0")/.." && pwd)
REMOTE_DIR=puppy-runtime

ssh "$HOST" "mkdir -p $REMOTE_DIR/runtime"
rsync -a --delete --exclude build/ "$ROOT/runtime/" "$HOST:$REMOTE_DIR/runtime/"

# Run detached so a dropped SSH connection doesn't kill a long build.
ssh "$HOST" "cd $REMOTE_DIR && mkdir -p runtime/build && rm -f runtime/build/status-$ARCH &&
	nohup sh -c 'PUPPY_BOOTSTRAP_FORCE=${PUPPY_BOOTSTRAP_FORCE:-} runtime/build-bootstrap.sh $ARCH > runtime/build/bootstrap-$ARCH.log 2>&1; echo \$? > runtime/build/status-$ARCH' >/dev/null 2>&1 &"
echo "Building on $HOST; log: ~/$REMOTE_DIR/runtime/build/bootstrap-$ARCH.log"

while :; do
	sleep 60
	status=$(ssh "$HOST" "cat $REMOTE_DIR/runtime/build/status-$ARCH 2>/dev/null" || true)
	[ -n "$status" ] && break
	ssh "$HOST" "grep -E '^\[\*\] Building|Building dependency' $REMOTE_DIR/runtime/build/bootstrap-$ARCH.log | tail -1" || true
done
if [ "$status" != 0 ]; then
	ssh "$HOST" "tail -40 $REMOTE_DIR/runtime/build/bootstrap-$ARCH.log"
	echo "remote build failed ($status)" >&2
	exit 1
fi

mkdir -p "$ROOT/app/src/main/assets/runtime"
rsync -a "$HOST:$REMOTE_DIR/app/src/main/assets/runtime/bootstrap-$ABI."{zip,json} "$ROOT/app/src/main/assets/runtime/"
echo "Copied bootstrap-$ABI.zip into app/src/main/assets/runtime/"
