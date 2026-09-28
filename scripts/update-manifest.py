#!/usr/bin/env python3
"""Writes the update manifest the app checks against.

Run by the release job once the packages are in place:

    python3 scripts/update-manifest.py dist dist/update.json \
        --repo RedSTARO/DAView --tag v1.2.0 --version v1.2.0 --package-version 1.2.140 \
        --notes-file notes.txt

One entry per package found in the directory, keyed by platform, with the
release-asset URL it will have and the file's SHA-256 so the app can refuse a
download that did not arrive whole. Unsigned APKs are skipped: they are the
input to signing, not something anyone should install.
"""
import argparse
import datetime
import hashlib
import json
import os

PLATFORMS = {".apk": "android", ".msi": "windows", ".deb": "linux", ".dmg": "macos"}


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dist", help="directory holding the packages")
    parser.add_argument("out", help="where to write the manifest")
    parser.add_argument("--repo", required=True, help="owner/name on GitHub")
    parser.add_argument("--tag", required=True, help="the release tag the assets are attached to")
    parser.add_argument("--version", required=True, help="the human label")
    parser.add_argument("--package-version", required=True, help="MAJOR.MINOR.PATCH the installers carry")
    parser.add_argument("--notes-file", help="release notes, plain text")
    args = parser.parse_args()

    assets = {}
    for name in sorted(os.listdir(args.dist)):
        key = PLATFORMS.get(os.path.splitext(name)[1].lower())
        if key is None or not name.startswith("DAView-") or "unsigned" in name:
            continue
        path = os.path.join(args.dist, name)
        assets[key] = {
            "url": "https://github.com/%s/releases/download/%s/%s" % (args.repo, args.tag, name),
            "name": name,
            "sha256": digest(path),
            "size": os.path.getsize(path),
        }

    notes = None
    if args.notes_file and os.path.exists(args.notes_file):
        with open(args.notes_file, encoding="utf-8") as f:
            notes = f.read().strip() or None

    manifest = {
        "version": args.version,
        "packageVersion": args.package_version,
        "publishedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "pageUrl": "https://github.com/%s/releases/tag/%s" % (args.repo, args.tag),
        "notes": notes,
        "assets": assets,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("update manifest: %s -> %s" % (args.package_version, ", ".join(sorted(assets)) or "no packages"))


if __name__ == "__main__":
    main()
