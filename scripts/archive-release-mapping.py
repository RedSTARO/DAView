#!/usr/bin/env python3
"""Keep each release mapping tied to its source revision and built package."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("android", "windows", "linux", "macos"), required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--package-dir", type=Path, required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    extension = {"android": "apk", "windows": "msi", "linux": "deb", "macos": "dmg"}[args.platform]
    packages = sorted(args.package_dir.glob(f"*.{extension}"))
    if len(packages) != 1:
        parser.error(f"Expected exactly one {extension} in {args.package_dir}, found {len(packages)}")
    if not args.mapping.is_file() or not args.mapping.stat().st_size:
        parser.error(f"Mapping is missing or empty: {args.mapping}")
    package = packages[0]
    if not package.stat().st_size:
        parser.error(f"Package is empty: {package}")
    provenance = {
        "platform": args.platform,
        "revision": args.revision,
        "version": args.version,
        "mappingSha256": sha256(args.mapping),
        "package": {"name": package.name, "sha256": sha256(package), "bytes": package.stat().st_size},
        "packageStage": "unsigned-before-apksigner" if args.platform == "android" else "installer",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(args.mapping, "mapping.txt")
        archive.writestr("provenance.json", json.dumps(provenance, ensure_ascii=False, indent=2) + "\n")
    print(args.output)


if __name__ == "__main__":
    main()
