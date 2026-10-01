#!/usr/bin/env bash
#
# Builds the native half of :ffmpeg-decoder: FFmpeg's DTS, TrueHD/MLP and
# AC-3/E-AC-3 decoders as shared libraries, plus media3's JNI wrapper around
# them, for the four Android ABIs.
#
#   scripts/build-ffmpeg-decoder.sh --ndk <NDK r27d> --ffmpeg <FFmpeg n6.1.6> [--out <dir>]
#   scripts/build-ffmpeg-decoder.sh --check-apk <apk>
#
# Writes <out>/jniLibs/<abi>/lib{avutil,swresample,avcodec,ffmpegJNI}.so and
# <out>/build-info.txt. The default <out> is build/ffmpeg-decoder, which is
# where ffmpeg-decoder/build.gradle.kts picks the libraries up. The module's
# README says why these formats, how the licence works out, and what the app
# does when the libraries are not there. --check-apk looks at an APK Gradle
# built from that output instead (see check_apk below).
#
# Linux x86_64 only, because that is the NDK host directory used below. Needs
# bash, make, git and a host C compiler (FFmpeg's configure wants one for its
# own checks). No CMake: the wrapper is one file and clang++ links it directly.
#
# Nothing is trusted on the way through. The NDK revision and the FFmpeg commit
# are checked against the pins below, the configuration FFmpeg arrived at is
# checked against what was asked for, and every library is checked before it
# is put in place: its name, what it links against, 16 KB page alignment, text
# relocations, the JNI entry points the Java side declares, leftover host
# paths, and the licence FFmpeg says it was built under. Every source file and
# header the compiler read is also searched for licence text other than the
# LGPL, and each one whose licence asks for a notice must have it in the
# NOTICE shipped in the APK. Any failure stops the script and leaves the
# previous output where it was.

set -euo pipefail

# ─── Pins ───────────────────────────────────────────────────────────────────────
# Written down here and nowhere else: CI reads them with --print-pins to install
# the NDK and to key its cache, and the NOTICE shipped in the APK must name the
# same FFmpeg (checked below).
#
# FFmpeg 6.1 rather than the release/6.0 that media3's README suggests. The
# wrapper needs the ch_layout / swr_alloc_set_opts2 API (FFmpeg 5.1+), which
# both have, but 6.0 has had no tagged release since 6.0.1 in November 2023,
# while 6.1.6 (June 2026) carries two and a half years more of backported
# fixes. That matters here: these decoders parse whatever file is played. The
# commit is checked, so a moved tag or a stray checkout cannot slip through.
FFMPEG_TAG=n6.1.6
FFMPEG_COMMIT=f1e3a2bf7a2f2cde936d1ed97f09a26853d20125
FFMPEG_GIT_URL=https://github.com/FFmpeg/FFmpeg.git
# r27d, the newest release of the r27 long-term-support line: Pkg.Revision in
# the NDK's source.properties, and the sdkmanager package "ndk;<this>".
NDK_VERSION=27.3.13750724
# The app's minSdk (androidMinSdk in gradle/libs.versions.toml). Linking against
# this API level is what stops the libraries from calling anything the oldest
# phone that installs the app does not have.
API_LEVEL=26

ALL_ABIS=(armeabi-v7a arm64-v8a x86 x86_64)
# FFmpeg's decoder names. dca is DTS, including DTS-HD MA (its lossless XLL
# extension) and DTS Express; truehd and mlp share one source file.
DECODERS=ac3,eac3,dca,truehd,mlp
LIBS=(avutil swresample avcodec ffmpegJNI)

# Files compiled into the libraries that carry licence text besides, or instead
# of, the LGPL, as check_licences finds them; a file it finds that is in
# neither list stops the build until someone has read its licence. These ask
# for their notice to go along with binaries, and NOTICE.txt must name each:
NOTICE_FILES=(
    libavcodec/jfdctfst.c          # IJG: "based in part on the work of the Independent JPEG Group"
    libavcodec/jfdctint_template.c # IJG, likewise
    libavcodec/jrevdct.c           # IJG, likewise
    libavcodec/arm/jrevdct_arm.S   # MIT; armeabi-v7a only
    libavcodec/faandct.c           # ISC
    libavutil/avsscanf.c           # MIT (musl)
    libavutil/fixed_dsp.c          # BSD 3-clause (MIPS Technologies), besides the LGPL
    libavutil/fixed_dsp.h          # the same notice
    libavutil/uuid.c               # BSD (Theodore Ts'o), besides the LGPL
)
# These carry other licence text as well, but nothing binaries must reproduce:
NO_NOTICE_FILES=(
    libavutil/adler32.c     # zlib licence, whose conditions cover source distributions
    libavutil/mathematics.c # Boost 1.0 for av_bessel_i0, which exempts object code
    libavutil/libm.h        # Boost 1.0 for an erf fallback, used only where libm has none
    libavutil/sha.c         # "based on public domain SHA-1 code"
)
# What counts as licence text other than the LGPL, matched case-insensitively
# against each file with comment leaders and line breaks folded into single
# spaces. FFmpeg's own LGPL header contains none of these phrases.
LICENCE_MARKERS="redistribution and use|permission is hereby granted|permission to use, copy, modify|independent jpeg group|boost software license|public domain|provided 'as-is'|copyright notice|without fee|apache license|gnu general public license|mozilla public license|mit licen[cs]e|spdx-license-identifier"

print_pins() {
    printf 'ffmpeg_tag=%s\n' "$FFMPEG_TAG"
    printf 'ffmpeg_commit=%s\n' "$FFMPEG_COMMIT"
    printf 'ndk_version=%s\n' "$NDK_VERSION"
    printf 'api_level=%s\n' "$API_LEVEL"
}

# Before anything else, so CI can ask without having an NDK or a checkout yet.
for arg in "$@"; do
    if [ "$arg" = --print-pins ]; then
        print_pins
        exit 0
    fi
done

usage() {
    cat <<EOF
Usage: $0 [options]
       $0 --check-apk APK

  --ndk DIR         Android NDK $NDK_VERSION (r27d).
                    Default: \$NDK_PATH, then \$ANDROID_NDK_HOME.
  --ffmpeg DIR      FFmpeg source at $FFMPEG_TAG. If DIR does not exist, the
                    tag is cloned into it (shallow). Default: \$FFMPEG_SRC.
  --out DIR         Output root; the libraries go to DIR/jniLibs/<abi>/,
                    replacing whatever was there.
                    Default: \$FFMPEG_DECODER_OUT, then <repo>/build/ffmpeg-decoder,
                    where Gradle looks; <repo>/build/ffmpeg-decoder-partial
                    when --abi leaves out any of the four ABIs.
  --work DIR        Intermediate build trees. Default: <out>/work.
  --abi ABI         Build only this ABI; repeatable. An APK built from such an
                    output installs only on those ABIs, which is why it has its
                    own default --out; point Gradle at it with
                    -PdaviewFfmpegJniLibs=build/ffmpeg-decoder-partial/jniLibs.
                    Default: all of ${ALL_ABIS[*]}.
  --jobs N          Parallel make jobs. Default: the number of CPUs.
  --allow-unpinned  Accept an NDK or FFmpeg other than the pinned ones. For
                    experiments only; the NOTICE in the APK would be wrong.
  --print-pins      Print the pinned versions as key=value lines and exit.
  --check-apk APK   Check an APK built with these libraries instead of
                    building: all 16 present, 16 KB-aligned in the file and
                    in their segments, licence files identical to the
                    repository's. Needs python3 and nothing else.
EOF
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

warn() {
    printf 'warning: %s\n' "$*" >&2
}

log() {
    printf '\n==> %s\n' "$*"
}

abspath() {
    case "$1" in
        /*) printf '%s\n' "$1" ;;
        *) printf '%s\n' "$PWD/$1" ;;
    esac
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$REPO_ROOT/ffmpeg-decoder"
JNI_SRC="$MODULE_DIR/src/main/jni/ffmpeg_jni.cc"
JAVA_DIR="$MODULE_DIR/src/main/java/androidx/media3/decoder/ffmpeg"
LICENSE_DIR="$MODULE_DIR/src/main/assets/licenses/ffmpeg"

# ─── APK check ──────────────────────────────────────────────────────────────────
# Everything below checks the libraries on their way into Gradle; this checks
# what came out, in the APK itself, so that a packaging mistake in the module
# (a wrong path, a filter, a source set) cannot pass for a build with FFmpeg:
#   - lib/<abi>/lib*.so for all four ABIs, and both licence files, are there;
#   - a library stored uncompressed starts on a 16 KB boundary in the file.
#     Android maps such a library straight out of the APK, which on 16 KB-page
#     devices (Android 15 and later) needs that alignment. A compressed one is
#     extracted at install time instead, where only its segments count;
#   - every LOAD segment is aligned to at least 16 KB, in the bytes Gradle
#     packaged (it may strip the libraries again on the way);
#   - the licence files are byte for byte the repository's.
check_apk() {
    local apk="$1"
    [ -f "$apk" ] || die "no APK at $apk"
    command -v python3 >/dev/null || die "--check-apk needs python3"
    python3 - "$apk" "$LICENSE_DIR" "${ALL_ABIS[*]}" "${LIBS[*]}" <<'PY'
import pathlib
import struct
import sys
import zipfile

apk, licence_dir = sys.argv[1], pathlib.Path(sys.argv[2])
abis, libs = sys.argv[3].split(), sys.argv[4].split()
PAGE = 16384
errors = []


def load_alignments(data):
    """p_align of every PT_LOAD program header of an ELF file."""
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    endian = "<" if data[5] == 1 else ">"
    if data[4] == 2:  # ELFCLASS64
        (phoff,) = struct.unpack_from(endian + "Q", data, 0x20)
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)
        align_at, align_format = 48, "Q"
    else:
        (phoff,) = struct.unpack_from(endian + "I", data, 0x1C)
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x2A)
        align_at, align_format = 28, "I"
    aligns = []
    for index in range(phnum):
        header = phoff + index * phentsize
        (p_type,) = struct.unpack_from(endian + "I", data, header)
        if p_type == 1:  # PT_LOAD
            aligns.append(struct.unpack_from(endian + align_format, data, header + align_at)[0])
    return aligns


with open(apk, "rb") as raw, zipfile.ZipFile(raw) as archive:
    entries = {info.filename: info for info in archive.infolist()}
    print(f"{'entry':36} {'bytes':>9} {'stored':>6} {'offset':>10} {'% 16K':>6} {'LOAD align':>10}")
    for abi in abis:
        for lib in libs:
            name = f"lib/{abi}/lib{lib}.so"
            info = entries.get(name)
            if info is None:
                errors.append(f"{name} is not in the APK")
                continue
            # The data follows the local header, whose extra field holds the
            # alignment padding and need not match the central directory's.
            raw.seek(info.header_offset)
            local = raw.read(30)
            if local[:4] != b"PK\x03\x04":
                errors.append(f"{name}: no local file header at {info.header_offset}")
                continue
            name_length, extra_length = struct.unpack("<HH", local[26:30])
            offset = info.header_offset + 30 + name_length + extra_length
            stored = info.compress_type == zipfile.ZIP_STORED
            data = archive.read(name)
            try:
                aligns = load_alignments(data)
            except (ValueError, IndexError, struct.error) as error:
                errors.append(f"{name}: {error}")
                continue
            smallest = min(aligns) if aligns else 0
            print(f"{name:36} {len(data):9d} {'yes' if stored else 'no':>6} {offset:10d} {offset % PAGE:6d} {smallest:10d}")
            if stored and offset % PAGE:
                errors.append(f"{name} is stored uncompressed at offset {offset}, which is not 16 KB-aligned")
            if smallest < PAGE:
                errors.append(f"{name} has LOAD segments aligned to {aligns}, below 16 KB")
    for licence in ("COPYING.LGPLv2.1", "NOTICE.txt"):
        name = f"assets/licenses/ffmpeg/{licence}"
        if name not in entries:
            errors.append(f"{name} is not in the APK")
        elif archive.read(name) != (licence_dir / licence).read_bytes():
            errors.append(f"{name} differs from {licence_dir / licence}")
        else:
            print(f"{name}: identical to the repository's")
    # Native code for another ABI (from some other dependency) would make the
    # APK install there, without FFmpeg.
    present = {name.split("/")[1] for name in entries if name.startswith("lib/") and name.count("/") == 2}
    others = sorted(present - set(abis))
    if others:
        print(f"warning: the APK also has native code for {', '.join(others)}, which gets no FFmpeg", file=sys.stderr)

for error in errors:
    print(f"error: {error}", file=sys.stderr)
sys.exit(1 if errors else 0)
PY
}

NDK="${NDK_PATH:-${ANDROID_NDK_HOME:-}}"
FFMPEG_SRC="${FFMPEG_SRC:-}"
OUT="${FFMPEG_DECODER_OUT:-}"
WORK=""
JOBS="$(nproc 2>/dev/null || echo 4)"
ABIS=()
ALLOW_UNPINNED=0
CHECK_APK=""

while [ $# -gt 0 ]; do
    case "$1" in
        --ndk | --ffmpeg | --out | --work | --abi | --jobs | --check-apk)
            [ $# -ge 2 ] || die "$1 needs a value"
            case "$1" in
                --ndk) NDK="$2" ;;
                --ffmpeg) FFMPEG_SRC="$2" ;;
                --out) OUT="$2" ;;
                --work) WORK="$2" ;;
                --abi) ABIS+=("$2") ;;
                --jobs) JOBS="$2" ;;
                --check-apk) CHECK_APK="$2" ;;
            esac
            shift 2
            ;;
        --allow-unpinned)
            ALLOW_UNPINNED=1
            shift
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            die "unknown argument: $1"
            ;;
    esac
done

# Ahead of the host checks below: it needs neither an NDK nor Linux.
if [ -n "$CHECK_APK" ]; then
    check_apk "$CHECK_APK" || die "$CHECK_APK failed the checks above"
    echo "$CHECK_APK: all FFmpeg libraries and licence files in place"
    exit 0
fi

# ─── Inputs ─────────────────────────────────────────────────────────────────────

[ "$(uname -s)" = Linux ] && [ "$(uname -m)" = x86_64 ] ||
    die "this script runs on Linux x86_64 hosts only (it uses the NDK's linux-x86_64 toolchain)"
for tool in make git; do
    command -v "$tool" >/dev/null || die "$tool is not installed"
done
command -v gcc >/dev/null || command -v cc >/dev/null ||
    die "no host C compiler (gcc or cc); FFmpeg's configure needs one for its own checks"

[ "${#ABIS[@]}" -gt 0 ] || ABIS=("${ALL_ABIS[@]}")
for abi in "${ABIS[@]}"; do
    case " ${ALL_ABIS[*]} " in
        *" $abi "*) ;;
        *) die "unknown ABI '$abi'; expected one of: ${ALL_ABIS[*]}" ;;
    esac
done
# In the usual order, each once.
requested=" ${ABIS[*]} "
ABIS=()
for abi in "${ALL_ABIS[@]}"; do
    case "$requested" in
        *" $abi "*) ABIS+=("$abi") ;;
    esac
done
PARTIAL_BUILD=0
[ "${#ABIS[@]}" = "${#ALL_ABIS[@]}" ] || PARTIAL_BUILD=1
# An APK built from fewer than all four ABIs installs on those alone, so such a
# build stays out of the directory Gradle reads unless --out puts it there.
if [ -z "$OUT" ]; then
    if [ "$PARTIAL_BUILD" = 1 ]; then
        OUT="$REPO_ROOT/build/ffmpeg-decoder-partial"
    else
        OUT="$REPO_ROOT/build/ffmpeg-decoder"
    fi
fi
[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] || die "--jobs must be a positive number, was '$JOBS'"

[ -n "$NDK" ] || die "no NDK given: pass --ndk DIR or set NDK_PATH"
[ -n "$FFMPEG_SRC" ] || die "no FFmpeg source given: pass --ffmpeg DIR or set FFMPEG_SRC"
NDK="$(abspath "$NDK")"
FFMPEG_SRC="$(abspath "$FFMPEG_SRC")"
OUT="$(abspath "$OUT")"
WORK="$(abspath "${WORK:-$OUT/work}")"
# FFmpeg's makefiles break on whitespace in paths.
for path in "$FFMPEG_SRC" "$OUT" "$WORK"; do
    case "$path" in
        *[[:space:]]*) die "path contains whitespace, which FFmpeg's build cannot handle: '$path'" ;;
    esac
done

[ -f "$NDK/source.properties" ] || die "$NDK is not an Android NDK (no source.properties)"
NDK_REVISION="$(sed -n 's/^Pkg\.Revision *= *//p' "$NDK/source.properties" | tr -d '\r')"
if [ "$NDK_REVISION" != "$NDK_VERSION" ]; then
    [ "$ALLOW_UNPINNED" = 1 ] ||
        die "the NDK at $NDK is $NDK_REVISION; this build is pinned to $NDK_VERSION (sdkmanager --install \"ndk;$NDK_VERSION\"), or pass --allow-unpinned"
    warn "using NDK $NDK_REVISION instead of the pinned $NDK_VERSION"
fi
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -x "$TOOLCHAIN/bin/clang" ] || die "no LLVM toolchain at $TOOLCHAIN"
READELF="$TOOLCHAIN/bin/llvm-readelf"
NM="$TOOLCHAIN/bin/llvm-nm"
STRIP="$TOOLCHAIN/bin/llvm-strip"

if [ ! -e "$FFMPEG_SRC" ]; then
    log "Cloning FFmpeg $FFMPEG_TAG into $FFMPEG_SRC"
    git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$FFMPEG_TAG" \
        "$FFMPEG_GIT_URL" "$FFMPEG_SRC"
fi
[ -f "$FFMPEG_SRC/configure" ] || die "$FFMPEG_SRC is not an FFmpeg source tree (no configure)"
if [ -e "$FFMPEG_SRC/.git" ]; then
    FFMPEG_REVISION="$(git -C "$FFMPEG_SRC" rev-parse HEAD)" || die "cannot read the commit of $FFMPEG_SRC"
    if [ "$FFMPEG_REVISION" != "$FFMPEG_COMMIT" ]; then
        [ "$ALLOW_UNPINNED" = 1 ] ||
            die "$FFMPEG_SRC is at $FFMPEG_REVISION; this build is pinned to $FFMPEG_TAG = $FFMPEG_COMMIT, or pass --allow-unpinned"
        warn "building FFmpeg $FFMPEG_REVISION instead of the pinned $FFMPEG_TAG"
    fi
    if [ -n "$(git -C "$FFMPEG_SRC" status --porcelain --untracked-files=no)" ]; then
        [ "$ALLOW_UNPINNED" = 1 ] || die "$FFMPEG_SRC has local modifications; the APK's NOTICE says unmodified"
        warn "building a modified FFmpeg tree"
    fi
else
    # A release tarball rather than a checkout.
    FFMPEG_REVISION="release $(cat "$FFMPEG_SRC/RELEASE" 2>/dev/null || echo unknown)"
    if [ "$FFMPEG_REVISION" != "release ${FFMPEG_TAG#n}" ]; then
        [ "$ALLOW_UNPINNED" = 1 ] ||
            die "$FFMPEG_SRC is $FFMPEG_REVISION; this build is pinned to $FFMPEG_TAG, or pass --allow-unpinned"
        warn "building FFmpeg $FFMPEG_REVISION instead of the pinned $FFMPEG_TAG"
    fi
fi
# Products of a configure run inside the source tree. The build below runs out
# of tree with the source reached through a relative "src" link, and FFmpeg's
# own guard against a configured source tree only covers absolute source paths;
# with -Isrc on the include path these would be picked up ahead of the build's
# own and silently change it. They are git-ignored, so the status check above
# cannot see them.
for generated in config.h config_components.h ffbuild/config.mak libavutil/avconfig.h \
    libavutil/ffversion.h libavcodec/codec_list.c; do
    [ ! -e "$FFMPEG_SRC/$generated" ] ||
        die "$FFMPEG_SRC/$generated exists: that tree has been configured in place. Run 'make distclean' in it, or use a fresh checkout"
done

[ -f "$JNI_SRC" ] || die "no JNI wrapper at $JNI_SRC"
[ -d "$JAVA_DIR" ] || die "no Java sources at $JAVA_DIR"
# The licence text in the APK has to be the one this source carries, and the
# NOTICE beside it has to name the FFmpeg actually built.
cmp -s "$FFMPEG_SRC/COPYING.LGPLv2.1" "$LICENSE_DIR/COPYING.LGPLv2.1" ||
    die "$LICENSE_DIR/COPYING.LGPLv2.1 differs from the one in $FFMPEG_SRC; copy it over"
if [ "$ALLOW_UNPINNED" != 1 ]; then
    grep -q -F "$FFMPEG_TAG" "$LICENSE_DIR/NOTICE.txt" && grep -q -F "$FFMPEG_COMMIT" "$LICENSE_DIR/NOTICE.txt" ||
        die "$LICENSE_DIR/NOTICE.txt does not name $FFMPEG_TAG ($FFMPEG_COMMIT); update it to match the pins"
fi

# The JNI entry points the Java side declares: every `native` method in the
# package, mangled the way the VM looks them up, plus JNI_OnLoad, which caches
# the growOutputBuffer method ID. If JNI_OnLoad is not exported the library
# still loads, the ID stays null, and the first decode that needs a bigger
# buffer crashes; checking the exports finds that at build time instead.
expected_jni_exports() {
    local file class method
    for file in "$JAVA_DIR"/*.java; do
        class="$(basename "$file" .java)"
        sed -n -E 's/^[[:space:]]*((public|protected|private)[[:space:]]+)?(static[[:space:]]+)?native[[:space:]].*[[:space:]]([A-Za-z0-9]+)[[:space:]]*\(.*/\4/p' "$file" |
            while read -r method; do
                printf 'Java_androidx_media3_decoder_ffmpeg_%s_%s\n' "$class" "$method"
            done
    done
    echo JNI_OnLoad
}
EXPECTED_JNI="$(expected_jni_exports | sort)"
[ "$(wc -l <<<"$EXPECTED_JNI")" -gt 1 ] || die "found no native methods under $JAVA_DIR"

# ─── Per-ABI settings ───────────────────────────────────────────────────────────
# The FFmpeg flags follow media3's build_ffmpeg.sh for each ABI: arch and cpu,
# armv7's float ABI and Cortex-A8 erratum fix, and no assembly on x86 and
# x86_64, where FFmpeg's assembly needs nasm, which the NDK does not ship. The
# wrapper's flags are what the NDK's CMake toolchain would pass, plus media3's
# -Bsymbolic on arm64.
abi_settings() {
    ASM_FLAG=""
    FF_CFLAGS=""
    FF_LDFLAGS=""
    JNI_CFLAGS=""
    JNI_LDFLAGS=""
    case "$1" in
        armeabi-v7a)
            TRIPLE=armv7a-linux-androideabi ARCH=arm CPU=armv7-a
            ELF_CLASS=ELF32 ELF_MACHINE=ARM
            FF_CFLAGS="-march=armv7-a -mfloat-abi=softfp"
            FF_LDFLAGS="-Wl,--fix-cortex-a8"
            JNI_CFLAGS="-march=armv7-a -mthumb"
            ;;
        arm64-v8a)
            TRIPLE=aarch64-linux-android ARCH=aarch64 CPU=armv8-a
            ELF_CLASS=ELF64 ELF_MACHINE=AArch64
            JNI_LDFLAGS="-Wl,-Bsymbolic"
            ;;
        x86)
            TRIPLE=i686-linux-android ARCH=x86 CPU=i686
            ELF_CLASS=ELF32 ELF_MACHINE="Intel 80386"
            ASM_FLAG=--disable-asm
            ;;
        x86_64)
            TRIPLE=x86_64-linux-android ARCH=x86_64 CPU=x86-64
            ELF_CLASS=ELF64 ELF_MACHINE="Advanced Micro Devices X86-64"
            ASM_FLAG=--disable-asm
            ;;
    esac
}

# Applied to every shared object, on every ABI:
#   -z max-page-size=16384  16 KB-aligned segments, without which the libraries
#                           do not load on 16 KB-page devices (Android 15+). r27
#                           does not do it by default; r28 and later do.
#   --build-id=sha1         what the NDK's toolchain passes, for symbolication.
#   --no-rosegment          likewise, for minSdk below 30: the unwinder on older
#                           releases cannot walk a separate read-only segment.
#   __BIONIC_NO_PAGE_SIZE_MACRO hides PAGE_SIZE, so nothing can bake in 4 KB.
COMMON_CFLAGS="-D__BIONIC_NO_PAGE_SIZE_MACRO"
COMMON_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 -Wl,--no-rosegment"

# ─── Checks ─────────────────────────────────────────────────────────────────────

expect_define() { # <abi> <name> <value> <header>...
    local abi="$1" name="$2" value="$3" found
    shift 3
    found="$(grep -h -E "^#define $name " "$@" || true)"
    [ "$found" = "#define $name $value" ] ||
        die "[$abi] configure produced '${found:-no $name}', expected '#define $name $value'"
}

# What FFmpeg's configure actually settled on, which is not always what it was
# asked for: it can quietly drop an option or pick up a library it found.
check_configuration() {
    local abi="$1" build="$2" name codecs parsers
    local headers=("$build/config.h" "$build/config_components.h")
    # The licence: LGPL 2.1 or later holds only while all three are off.
    for name in CONFIG_GPL CONFIG_NONFREE CONFIG_VERSION3; do
        expect_define "$abi" "$name" 0 "${headers[@]}"
    done
    expect_define "$abi" CONFIG_SHARED 1 "${headers[@]}"
    expect_define "$abi" CONFIG_STATIC 0 "${headers[@]}"
    # Threads stay on. Without them FFmpeg's one-time table setup is not
    # thread-safe, and the player can open decoders on more than one thread.
    expect_define "$abi" HAVE_PTHREADS 1 "${headers[@]}"
    # Nothing picked up on the way: the NDK's sysroot has zlib, Vulkan and V4L2
    # headers, and configure would otherwise build in whatever it found.
    for name in CONFIG_ZLIB CONFIG_BZLIB CONFIG_LZMA CONFIG_ICONV CONFIG_VULKAN \
        CONFIG_V4L2_M2M CONFIG_MEDIACODEC CONFIG_JNI; do
        expect_define "$abi" "$name" 0 "${headers[@]}"
    done
    # Exactly the five decoders. The only parsers are the two that the AC-3 and
    # MLP decoders select for their own header parsing; media3 never calls a
    # parser, because its extractors frame the stream themselves.
    codecs="$(grep -o -E '&ff_[a-z0-9_]+' "$build/libavcodec/codec_list.c" | sed 's/^&ff_//' | sort | tr '\n' ' ' || true)"
    [ "$codecs" = "ac3_decoder dca_decoder eac3_decoder mlp_decoder truehd_decoder " ] ||
        die "[$abi] libavcodec would contain the codecs: $codecs"
    parsers="$(grep -o -E '&ff_[a-z0-9_]+' "$build/libavcodec/parser_list.c" | sed 's/^&ff_//' | sort | tr '\n' ' ' || true)"
    [ "$parsers" = "ac3_parser mlp_parser " ] ||
        die "[$abi] libavcodec would contain the parsers: $parsers"
}

in_list() { # <word> <list>...
    local word="$1" item
    shift
    for item in "$@"; do
        [ "$item" != "$word" ] || return 0
    done
    return 1
}

# Licence text other than the LGPL in anything that went into the libraries:
# every source and header the compiler read for FFmpeg (from its dependency
# files, which leave out the NDK's own headers) and the FFmpeg headers the
# wrapper is compiled against. Each file is searched in full, not just its
# first comment: some carry a BSD notice next to the LGPL one, or further down
# above the code it covers. Whatever turns up must be in NOTICE_FILES, with
# its notice in NOTICE.txt, or in NO_NOTICE_FILES.
declare -A LICENCE_FILES_SEEN OTHER_LICENCES
check_licences() {
    local abi="$1" build="$2" object name path text
    local -a names=() found=() unknown=()
    local -A paths=()
    # A source without a dependency file would escape the search.
    while IFS= read -r -d '' object; do
        [ -f "${object%.o}.d" ] ||
            die "[$abi] ${object#"$build/"} has no dependency file, so its sources cannot be searched for licences"
    done < <(find "$build" -path "$build/stage" -prune -o -name '*.o' -type f -print0)
    # Dependencies are named relative to the build directory, the sources as
    # src/..., sometimes through a parent reference (src/libavutil/../compat/).
    while IFS= read -r name; do
        paths[$name]="$FFMPEG_SRC/$name"
    done < <(find "$build" -path "$build/stage" -prune -o -name '*.d' -type f -print0 |
        xargs -0 cat | LC_ALL=C tr -s ' \t\\' '\n' | sed -n 's#^src/##p' |
        sed -E ':a; s#(^|/)[^/.][^/]*/\.\./#\1#; ta' | LC_ALL=C sort -u)
    [ "${#paths[@]}" -ge 100 ] ||
        die "[$abi] the dependency files name only ${#paths[@]} FFmpeg sources; are they still being written?"
    while IFS= read -r name; do
        paths[$name]="$build/stage/include/$name"
    done < <(cd "$build/stage/include" && find . -type f | sed 's#^\./##')

    mapfile -t names < <(printf '%s\n' "${!paths[@]}" | LC_ALL=C sort)
    for name in "${names[@]}"; do
        path="${paths[$name]}"
        [ -f "$path" ] || die "[$abi] $name went into the build, but there is no $path"
        text="$(LC_ALL=C tr -s '\r\n\t */#;' ' ' <"$path")"
        LC_ALL=C grep -a -i -q -E -- "$LICENCE_MARKERS" <<<"$text" || continue
        found+=("$name")
        LICENCE_FILES_SEEN[$name]=1
        if in_list "$name" "${NOTICE_FILES[@]}"; then
            grep -q -F -- "$name" "$LICENSE_DIR/NOTICE.txt" ||
                die "[$abi] $name is compiled in and its licence asks for a notice, but NOTICE.txt does not name it"
        elif ! in_list "$name" "${NO_NOTICE_FILES[@]}"; then
            unknown+=("$name")
        fi
    done
    [ "${#unknown[@]}" = 0 ] ||
        die "[$abi] compiled in, with licence text other than the LGPL that this script does not know: ${unknown[*]}. Read each one; if its licence asks for a notice in binary distributions, put the notice in NOTICE.txt and the file in NOTICE_FILES, otherwise put the file in NO_NOTICE_FILES"
    OTHER_LICENCES[$abi]="${found[*]}"
    log "[$abi] licences: searched ${#names[@]} files; ${#found[@]} carry other licence text, all accounted for"
}

# Everything Android's loader and the app will hold these libraries to.
verify_libraries() {
    local abi="$1" dir="$2"
    local lib file header dynamic soname needed dependency phdrs align flags sections path exports
    for lib in "${LIBS[@]}"; do
        file="$dir/lib$lib.so"
        [ -s "$file" ] || die "[$abi] lib$lib.so is missing or empty"

        header="$("$READELF" -h "$file")" || die "[$abi] lib$lib.so is not an ELF file"
        grep -q -E "^ *Class: +$ELF_CLASS\$" <<<"$header" || die "[$abi] lib$lib.so is not $ELF_CLASS"
        grep -q -E "^ *Machine: +$ELF_MACHINE\$" <<<"$header" || die "[$abi] lib$lib.so is not built for $ELF_MACHINE"

        # The loader resolves dependencies by SONAME, and the APK can only carry
        # files named lib*.so, so a versioned name (libavcodec.so.60) would make
        # the wrapper unloadable. FFmpeg's android target already avoids that.
        dynamic="$("$READELF" -d "$file")"
        soname="$(sed -n 's/.*(SONAME).*\[\(.*\)\].*/\1/p' <<<"$dynamic")"
        [ "$soname" = "lib$lib.so" ] || die "[$abi] lib$lib.so has SONAME '$soname'"

        needed="$(sed -n 's/.*(NEEDED).*\[\(.*\)\].*/\1/p' <<<"$dynamic")"
        for dependency in $needed; do
            case "$dependency" in
                libc.so | libm.so | libdl.so | liblog.so | libandroid.so) ;; # NDK stable APIs
                libavutil.so | libswresample.so | libavcodec.so)              # shipped alongside
                    [ "$dependency" != "lib$lib.so" ] || die "[$abi] lib$lib.so needs itself"
                    ;;
                *) die "[$abi] lib$lib.so needs $dependency, which is neither an NDK system library nor shipped with it" ;;
            esac
        done
        if [ "$lib" = ffmpegJNI ]; then
            for dependency in libavcodec.so libswresample.so libavutil.so; do
                grep -q -x -F "$dependency" <<<"$needed" || die "[$abi] libffmpegJNI.so does not link $dependency"
            done
        fi
        # Android has refused to load libraries with text relocations since 6.0.
        ! grep -q TEXTREL <<<"$dynamic" || die "[$abi] lib$lib.so has text relocations"
        ! grep -q -E '\((RPATH|RUNPATH)\)' <<<"$dynamic" || die "[$abi] lib$lib.so carries a search path"

        phdrs="$("$READELF" -lW "$file")"
        grep -q -E '^ *LOAD ' <<<"$phdrs" || die "[$abi] lib$lib.so has no LOAD segments"
        while read -r align; do
            ((align >= 16384)) || die "[$abi] lib$lib.so has a LOAD segment aligned to $align, below 16 KB"
        done < <(awk '$1 == "LOAD" { print $NF }' <<<"$phdrs")
        flags="$(awk '$1 == "GNU_STACK" { f = ""; for (i = 7; i < NF; i++) f = f $i; print f }' <<<"$phdrs")"
        [ -n "$flags" ] || die "[$abi] lib$lib.so has no GNU_STACK header"
        [[ "$flags" != *E* ]] || die "[$abi] lib$lib.so asks for an executable stack"

        sections="$("$READELF" -SW "$file")"
        ! grep -q -E ' \.(symtab|debug_[a-z_]+) ' <<<"$sections" || die "[$abi] lib$lib.so is not stripped"

        # The build runs out of tree through a relative "src" link and finds
        # the compilers on PATH so that no absolute path ends up in the
        # binaries: FFmpeg embeds its configure line, and assertions embed
        # source file names. A path here would make builds differ by machine.
        for path in "$FFMPEG_SRC" "$NDK" "$WORK" "$OUT" "$REPO_ROOT" "${HOME:-/}"; do
            [ "$path" != / ] || continue
            ! grep -a -q -F -- "$path" "$file" || die "[$abi] lib$lib.so contains the host path $path"
        done

        if [ "$lib" != ffmpegJNI ]; then
            # What av*_license() returns; it changes only with the options above.
            grep -a -q -F "lib$lib license: LGPL version 2.1 or later" "$file" ||
                die "[$abi] lib$lib.so does not report 'LGPL version 2.1 or later'"
        fi
    done

    exports="$("$NM" -D --defined-only "$dir/libffmpegJNI.so" | awk '{ print $NF }' | grep -E '^(Java_|JNI_OnLoad$)' | sort || true)"
    [ "$exports" = "$EXPECTED_JNI" ] ||
        die "[$abi] libffmpegJNI.so exports:
$exports
but the Java sources declare:
$EXPECTED_JNI"
}

# ─── Build ──────────────────────────────────────────────────────────────────────

# The configure and link commands below name the tools without a path.
export PATH="$TOOLCHAIN/bin:$PATH"

# Built into a staging directory that replaces the output only once every ABI
# has passed every check.
STAGING="$OUT/jniLibs.new"
rm -rf "$STAGING" "$OUT/jniLibs.partial"
mkdir -p "$STAGING" "$WORK"
# Configure's scratch files and the compilers' temporaries go to the work tree
# as well, so a build writes nothing outside its own directories.
export TMPDIR="$WORK/tmp"
mkdir -p "$TMPDIR"

declare -A SECONDS_TAKEN CONFIGURE_LINE
total_start=$SECONDS

log "FFmpeg $FFMPEG_TAG ($FFMPEG_REVISION), NDK $NDK_REVISION, API $API_LEVEL, decoders $DECODERS"
log "ABIs: ${ABIS[*]}; output: $OUT/jniLibs; work: $WORK; make -j$JOBS"

for abi in "${ABIS[@]}"; do
    abi_start=$SECONDS
    abi_settings "$abi"
    build="$WORK/$abi"
    dest="$STAGING/$abi"
    rm -rf "$build"
    mkdir -p "$build" "$dest"
    # Out of tree, with the source as the relative path "src": FFmpeg's
    # configure recognises that layout and then refers to every source file
    # relatively, so no absolute path reaches __FILE__ or the configure line.
    # The checkout itself stays clean and serves every ABI.
    ln -s "$FFMPEG_SRC" "$build/src"

    configure_args=(
        --target-os=android
        --arch="$ARCH"
        --cpu="$CPU"
        --cross-prefix="$TRIPLE$API_LEVEL-"
        --nm=llvm-nm
        --ar=llvm-ar
        --ranlib=llvm-ranlib
        --strip=llvm-strip
        # Shared rather than static, as media3 builds it: LGPL lets anyone
        # swap these libraries for their own build, and separate .so files are
        # the plain way to make that possible.
        --enable-shared
        --disable-static
        --disable-programs
        --disable-doc
        # No debug info. It is stripped anyway, and while it is there lld's
        # build ID hashes it, build directory path included, so two otherwise
        # identical builds on different machines would differ in those bytes.
        --disable-debug
        --disable-everything
        --disable-avdevice
        --disable-avformat
        --disable-swscale
        --disable-postproc
        --disable-avfilter
        --disable-network
        --disable-symver
        # No external library found on the way in. This also covers media3's
        # --disable-vulkan and --disable-v4l2-m2m; iconv needs its own switch
        # because --disable-autodetect still probes libc for it.
        --disable-autodetect
        --disable-iconv
        --enable-swresample
        --enable-decoder="$DECODERS"
        --extra-cflags="$COMMON_CFLAGS${FF_CFLAGS:+ $FF_CFLAGS}"
        --extra-ldflags="$COMMON_LDFLAGS${FF_LDFLAGS:+ $FF_LDFLAGS}"
    )
    [ -z "$ASM_FLAG" ] || configure_args+=("$ASM_FLAG")

    # Its one warning, that <triple>-pkg-config does not exist, is expected:
    # nothing in this configuration is looked up through pkg-config.
    log "[$abi] configure"
    if ! (cd "$build" && ./src/configure "${configure_args[@]}") >"$build/configure.log" 2>&1; then
        cat "$build/configure.log"
        tail -n 40 "$build/ffbuild/config.log" 2>/dev/null || true
        die "[$abi] FFmpeg's configure failed"
    fi
    cat "$build/configure.log"
    check_configuration "$abi" "$build"

    log "[$abi] make"
    if ! make -C "$build" -j"$JOBS" >"$build/make.log" 2>&1; then
        grep -n -E 'error|Error' "$build/make.log" | head -n 40 || true
        tail -n 40 "$build/make.log"
        die "[$abi] building FFmpeg failed; the full log is $build/make.log"
    fi
    make -C "$build" install-libs install-headers prefix="$build/stage" >>"$build/make.log" 2>&1 ||
        die "[$abi] installing FFmpeg into $build/stage failed; see $build/make.log"
    check_licences "$abi" "$build"
    for lib in avutil swresample avcodec; do
        cp "$build/stage/lib/lib$lib.so" "$dest/lib$lib.so"
    done

    log "[$abi] libffmpegJNI.so"
    # Compiled from the source's own directory so that only its file name, not
    # its path, is ever seen by the compiler. -static-libstdc++ because the
    # NDK's clang++ would otherwise want libc++_shared.so in the APK, which the
    # wrapper has no use for (CMake's default STL, c++_static, is the same).
    # Hidden visibility keeps the exports to the JNI entry points.
    # shellcheck disable=SC2086 # the flag variables are deliberately split
    (cd "$(dirname "$JNI_SRC")" && "$TRIPLE$API_LEVEL-clang++" \
        -std=gnu++11 -O2 -DNDEBUG -fPIC -fvisibility=hidden \
        -DANDROID -D_FORTIFY_SOURCE=2 $COMMON_CFLAGS \
        -fdata-sections -ffunction-sections -funwind-tables -fstack-protector-strong \
        -no-canonical-prefixes -Wformat -Werror=format-security \
        $JNI_CFLAGS \
        -I"$build/stage/include" \
        -shared -Wl,-soname,libffmpegJNI.so \
        -o "$dest/libffmpegJNI.so" "$(basename "$JNI_SRC")" \
        -L"$dest" -lavcodec -lswresample -lavutil -llog -landroid \
        -static-libstdc++ \
        $COMMON_LDFLAGS -Wl,--no-undefined -Wl,--no-undefined-version -Wl,--gc-sections \
        -Wl,--fatal-warnings -Qunused-arguments \
        $JNI_LDFLAGS) ||
        die "[$abi] building libffmpegJNI.so failed"

    "$STRIP" --strip-unneeded "$dest"/*.so
    verify_libraries "$abi" "$dest"

    CONFIGURE_LINE[$abi]="$(grep -a -o -E -- '--target-os=android[[:print:]]*' "$dest/libavcodec.so" | sed -n 1p || true)"
    [ -n "${CONFIGURE_LINE[$abi]}" ] || die "[$abi] no configure line embedded in libavcodec.so"
    case "${CONFIGURE_LINE[$abi]}" in
        *--enable-gpl* | *--enable-nonfree* | *--enable-version3*)
            die "[$abi] built with a licence-changing option: ${CONFIGURE_LINE[$abi]}"
            ;;
    esac
    SECONDS_TAKEN[$abi]=$((SECONDS - abi_start))
    log "[$abi] done in ${SECONDS_TAKEN[$abi]} s"
done

# ─── Output ─────────────────────────────────────────────────────────────────────

{
    echo "FFmpeg $FFMPEG_TAG ($FFMPEG_REVISION) from $FFMPEG_GIT_URL"
    echo "NDK $NDK_REVISION, API level $API_LEVEL, decoders $DECODERS"
    echo "Built $(date -u +%Y-%m-%dT%H:%M:%SZ) on $(uname -srm)"
    for abi in "${ABIS[@]}"; do
        echo
        echo "[$abi] configure ${CONFIGURE_LINE[$abi]}"
        for lib in "${LIBS[@]}"; do
            printf '  %-20s %10d bytes\n' "lib$lib.so" "$(stat -c %s "$STAGING/$abi/lib$lib.so")"
        done
        echo "  other licence text in: ${OTHER_LICENCES[$abi]}"
    done
} >"$STAGING/build-info.txt"

rm -rf "$OUT/jniLibs"
mv "$STAGING/build-info.txt" "$OUT/build-info.txt"
mv "$STAGING" "$OUT/jniLibs"

# Only a build of every ABI sees every file (jrevdct_arm.S is armv7's alone).
if [ "$PARTIAL_BUILD" = 0 ]; then
    for name in "${NOTICE_FILES[@]}" "${NO_NOTICE_FILES[@]}"; do
        [ -n "${LICENCE_FILES_SEEN[$name]:-}" ] ||
            warn "$name is listed in this script but was not compiled for any ABI; if FFmpeg no longer has it, drop it from the list (and its notice from NOTICE.txt)"
    done
fi

log "Done in $((SECONDS - total_start)) s"
printf '%-12s %10s %10s %10s %10s %10s %6s\n' ABI avutil swresample avcodec ffmpegJNI total seconds
for abi in "${ABIS[@]}"; do
    total=0
    sizes=()
    for lib in "${LIBS[@]}"; do
        size="$(stat -c %s "$OUT/jniLibs/$abi/lib$lib.so")"
        sizes+=("$size")
        total=$((total + size))
    done
    printf '%-12s %10d %10d %10d %10d %10d %6d\n' "$abi" "${sizes[@]}" "$total" "${SECONDS_TAKEN[$abi]}"
done
echo "Libraries: $OUT/jniLibs"
echo "Build info: $OUT/build-info.txt"
if [ "$PARTIAL_BUILD" = 1 ]; then
    echo "Only ${ABIS[*]}: an APK built from these installs on no other ABI."
    echo "Gradle uses them with -PdaviewFfmpegJniLibs=$OUT/jniLibs"
fi
