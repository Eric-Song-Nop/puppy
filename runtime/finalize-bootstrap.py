#!/usr/bin/env python3
"""Prepares a built bootstrap for bundling.

Replaces the APT sources with a commented placeholder: upstream Termux
repositories carry binaries built for com.termux paths and must never be
used for this app's prefix (design §5.3). Puppy has no repository of its
own yet, so `pkg install` is disabled rather than pointed at the wrong one.

Then rewrites the manifest's sha256.

Usage: finalize-bootstrap.py <bootstrap.zip> <manifest.json>
"""
import hashlib
import json
import os
import sys
import tempfile
import zipfile

SOURCES = """\
# Puppy has no package repository yet.
#
# Packages from the official Termux repositories are built for com.termux
# paths and do not work in this app, so none is configured here.
# Add a repository built for /data/data/org.pocketlab.puppy/files/usr:
#   deb https://<your-repo> stable main
"""


def main(zip_path: str, manifest_path: str) -> None:
    fd, tmp = tempfile.mkstemp(suffix=".zip", dir=os.path.dirname(zip_path))
    os.close(fd)
    replaced = []
    with zipfile.ZipFile(zip_path) as src, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            name = info.filename
            if name == "etc/apt/sources.list":
                dst.writestr(info, SOURCES)
                replaced.append(name)
            elif name.startswith("etc/apt/sources.list.d/") and not name.endswith("/"):
                replaced.append(name)  # dropped
            else:
                dst.writestr(info, src.read(info))
    os.replace(tmp, zip_path)

    with open(zip_path, "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()
    with open(manifest_path) as f:
        manifest = json.load(f)
    manifest["sha256"] = sha
    manifest["repository"] = None
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print(f"replaced {replaced}; sha256 {sha}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
