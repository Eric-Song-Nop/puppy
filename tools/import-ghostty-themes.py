#!/usr/bin/env python3
"""Import the Ghostty theme snapshot pinned by the Ghostty core commit.

Downloads the exact archive referenced by Ghostty 9f13eb75's build.zig.zon,
verifies its SHA-256, and writes:

  app/src/main/assets/themes/ghostty/<Theme Name>   (upstream files, unchanged)
  app/src/main/assets/themes/index.json             (name, path, sha256, source)
  app/src/main/assets/themes/NOTICE                 (provenance)

Usage: python3 tools/import-ghostty-themes.py
"""
import hashlib
import io
import json
import os
import shutil
import sys
import tarfile
import urllib.request

GHOSTTY_COMMIT = "9f13eb7526cc070a2b829f2aae41a44d5cce12bf"
SNAPSHOT = "ghostty-themes-release-20261005-151109-31756e7"
URL = f"https://deps.files.ghostty.org/{SNAPSHOT}.tgz"
SHA256 = "574403c123bdd892699f7894bf5f85f81ce3ca0d9af18ce9885041a8b9d1e3f0"
# Zig package hash recorded in Ghostty's build.zig.zon for the same archive.
ZIG_HASH = "N-V-__8AADNBBQDD4MFOa3Y7R_A8PHIeWQ1IZdOiBFZS3nZ7"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "themes")


def main() -> int:
    if len(sys.argv) > 1:
        # Optional pre-downloaded archive; still verified below.
        with open(sys.argv[1], "rb") as f:
            data = f.read()
    else:
        print(f"downloading {URL}")
        # The host rejects urllib's default User-Agent.
        req = urllib.request.Request(URL, headers={"User-Agent": "puppy-theme-import/1"})
        data = urllib.request.urlopen(req).read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != SHA256:
        print(f"sha256 mismatch: got {digest}, expected {SHA256}", file=sys.stderr)
        return 1

    themes_dir = os.path.join(OUT, "ghostty")
    shutil.rmtree(themes_dir, ignore_errors=True)
    os.makedirs(themes_dir)

    entries = []
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        for member in tar.getmembers():
            if not member.isfile():
                continue
            parts = member.name.split("/")
            # Only flat files directly under ghostty/; reject anything else.
            if len(parts) != 2 or parts[0] != "ghostty" or parts[1] in ("", ".", ".."):
                print(f"skipping unexpected entry {member.name}", file=sys.stderr)
                continue
            name = parts[1]
            content = tar.extractfile(member).read()
            content.decode("utf-8")  # must be valid UTF-8 text
            with open(os.path.join(themes_dir, name), "wb") as f:
                f.write(content)
            entries.append({
                "name": name,
                "path": f"themes/ghostty/{name}",
                "sha256": hashlib.sha256(content).hexdigest(),
            })

    entries.sort(key=lambda e: e["name"].casefold())
    index = {
        "source": {
            "snapshot": SNAPSHOT,
            "url": URL,
            "sha256": SHA256,
            "zigHash": ZIG_HASH,
            "ghosttyCommit": GHOSTTY_COMMIT,
        },
        "themes": entries,
    }
    with open(os.path.join(OUT, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)
        f.write("\n")

    with open(os.path.join(OUT, "NOTICE"), "w", encoding="utf-8") as f:
        f.write(
            f"Theme files in ghostty/ are an unmodified copy of {SNAPSHOT}\n"
            f"({URL}, sha256 {SHA256}),\n"
            f"the theme snapshot pinned by Ghostty commit {GHOSTTY_COMMIT}.\n\n"
            "Ghostty publishes these themes from the iTerm2-Color-Schemes project\n"
            "(https://github.com/mbadolato/iTerm2-Color-Schemes), which is distributed\n"
            "under the MIT License; individual theme credits are recorded there.\n"
            "The archive itself contains no license file.\n\n"
            "x11-rgb.txt is Ghostty's X11 color name table (src/terminal/res/rgb.txt\n"
            f"at commit {GHOSTTY_COMMIT}, MIT License), used to resolve named colors\n"
            "the same way Ghostty does. It is copied by hand, not by this script.\n"
        )
    print(f"wrote {len(entries)} themes to {themes_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
