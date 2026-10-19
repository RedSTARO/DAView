#!/usr/bin/env bash
# Usage: scripts/repack-deb.sh <package.deb>
#
# Rewrites a .deb that jpackage produced on an Ubuntu 24.04 runner so that it
# installs on the distributions people still run, in place.
#
# - Dependencies. jpackage names the packages that hold the libraries the
#   bundled runtime links against, as they are called on the machine that
#   built it. Ubuntu 24.04 and Debian 13 renamed some of them in the 64-bit
#   time_t transition (libasound2 became libasound2t64, libpng16-16 became
#   libpng16-16t64), and Debian 12 and Ubuntu 22.04 have no package by the new
#   name, so the package could not be installed there at all. Every
#   dependency on a "<name>t64" package becomes "<name>t64 | <name>"; the
#   renamed packages also provide the old name, so newer systems are
#   unaffected.
# - Compression. Ubuntu's dpkg writes zstd members, which dpkg on Debian 11
#   cannot read. xz is read by every dpkg in use and comes out smaller.
#
# File contents, modes and maintainer scripts are unchanged; ownership is
# root's, as in the original.
set -euo pipefail

deb="${1:?usage: $0 <package.deb>}"
[ -f "$deb" ] || { echo "no such file: $deb" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

dpkg-deb -R "$deb" "$work/pkg"
control="$work/pkg/DEBIAN/control"

python3 - "$control" <<'PY'
import re
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()


def widen(match):
    out = []
    for dep in (d.strip() for d in match.group(1).split(",")):
        if not dep:
            continue
        name = dep.split()[0]
        if name.endswith("t64") and "|" not in dep:
            dep = "%s | %s" % (dep, name[:-3])
        out.append(dep)
    return "Depends: " + ", ".join(out)


text, count = re.subn(r"^Depends:[ \t]*(.*)$", widen, text, count=1, flags=re.M)
if count != 1:
    sys.exit("the package has no Depends field")
open(path, "w", encoding="utf-8", newline="\n").write(text)
PY

dpkg-deb --root-owner-group -Zxz -z9 -b "$work/pkg" "$work/out.deb" >/dev/null
mv "$work/out.deb" "$deb"

echo "Depends: $(dpkg-deb -f "$deb" Depends)"
