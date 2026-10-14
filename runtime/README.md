# Local runtime

The APK can carry a bootstrap — a minimal Termux-derived Android userland
(bash, coreutils, apt/dpkg, certificates…) — built for Puppy's own prefix,
`/data/data/org.pocketlab.puppy/files/usr` (design §5).

```sh
runtime/remote-build.sh <x86_64-linux-ssh-host> aarch64   # recommended
runtime/build-bootstrap.sh aarch64                        # or locally (Docker)
./gradlew :app:assembleDebug   # bundles app/src/main/assets/runtime/*
```

Build on an x86_64 Linux host: the builder image is amd64-only, and Apple
Silicon has to emulate it (several times slower). `remote-build.sh` copies
the scripts there, builds detached (a dropped SSH session doesn't stop it)
and copies `bootstrap-<abi>.zip` and its manifest back into the gitignored
assets directory. The remote container keeps Termux's build cache, so later
builds only recompile what changed.

`build-bootstrap.sh` pins `termux/termux-packages` to one commit, switches the package
name, applies `runtime/patches/*.patch` and runs the upstream
`build-bootstraps.sh` in Termux's builder image (sources are copied into a
persistent container rather than bind-mounted). Every package is built from
source for our prefix; `com.termux` binaries are never reused. With no Puppy
package repository yet, `finalize-bootstrap.py` replaces the APT sources with
a commented placeholder.

At first launch `RuntimeManager` checks the archive's SHA-256 and prefix,
extracts it to a staging directory under a cross-process lock, rejects unsafe
paths and symlinks, activates it by rename, runs the bootstrap's second stage
and a shell smoke test, then marks it ready. HOME is never touched.

Builds without a bundled bootstrap still work: local terminals use Android's
`/system/bin/sh` and the UI says so.

## Not done yet

- The package repository: `sources.list` must point to Puppy's own signed
  APT repository (design §5.3), whose domain and signing key are not chosen
  yet (design §15 item 1). Until then `pkg install` has no repository.
- The bootstrap built by this script has not yet passed gate G0 on a device.
