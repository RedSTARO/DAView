# :ffmpeg-decoder

FFmpeg audio decoding for the Android player. It plays the audio tracks that
Blu-ray rips and Matroska files carry and phones cannot decode:

| Format | FFmpeg decoder | Why the phone needs it |
|---|---|---|
| DTS, DTS-HD MA (lossless), DTS Express | `dca` | Few phones ship a DTS decoder in MediaCodec. |
| Dolby TrueHD, MLP | `truehd`, `mlp` | Few phones ship a TrueHD decoder. |
| AC-3, E-AC-3 (also E-AC-3 JOC) | `ac3`, `eac3` | Many phones have Dolby decoders; this covers the ones that do not. |

Without this module, media3 has no decoder for those tracks on such phones.
(`mlp` is built because it shares its source with `truehd`; no media3 MIME
type maps to it.)

## What is in here

- `src/main/java/androidx/media3/decoder/ffmpeg/` and `src/main/jni/ffmpeg_jni.cc`:
  media3's FFmpeg extension, copied **unchanged** from
  [androidx/media 1.11.0](https://github.com/androidx/media/tree/1.11.0/libraries/decoder_ffmpeg/src/main),
  the media3 version the app uses. Google does not publish it to Maven, and
  using someone else's prebuilt AAR would mean shipping binaries nobody here
  built. `proguard-rules.txt` and `src/main/AndroidManifest.xml` are upstream's
  too. The package stays `androidx.media3.decoder.ffmpeg`, because media3's
  `DefaultRenderersFactory` finds `FfmpegAudioRenderer` by that class name (it
  also finds `ExperimentalFfmpegVideoRenderer`, which in 1.11.0 is a stub that
  supports nothing; it is part of upstream's sources and was kept with them).
- `src/main/assets/licenses/ffmpeg/`: FFmpeg's licence text and a notice saying
  which FFmpeg this is, how it was configured, where its source is, and the
  notices of the few files under other permissive licences. Packaged into the
  APK as assets, so they travel with the libraries.
- `build.gradle.kts`: a plain Java Android library. It compiles the Java
  sources and packages whatever native libraries `scripts/build-ffmpeg-decoder.sh`
  left in `build/ffmpeg-decoder/jniLibs/<abi>/` at the repository root
  (override: `-PdaviewFfmpegJniLibs=<dir>`, relative to the root or absolute).
  Gradle does not build any native code.

Depending on the module is not enough by itself: the player's
`DefaultRenderersFactory` must have its extension renderer mode set.
`EXTENSION_RENDERER_MODE_ON` puts FFmpeg after the MediaCodec renderer, so it
only takes formats no decoder on the phone accepts and a hardware Dolby decoder
keeps priority; `EXTENSION_RENDERER_MODE_PREFER` would put FFmpeg first.
Either way the renderer list gains two entries (the audio renderer and the
video stub), which shifts any renderer index counted by hand.

## Without the native libraries

The module still compiles and the app still runs. `System.loadLibrary("ffmpegJNI")`
throws `UnsatisfiedLinkError`, media3's `LibraryLoader` catches it and logs
`Failed to load [ffmpegJNI]`, `FfmpegLibrary.isAvailable()` returns false, and
`FfmpegAudioRenderer` reports every format as unsupported — playback is what it
was before this module existed. That is the state of a local Gradle build on a
machine that has not run the script. CI does not let such a build through: the
Android job fails when any of the 16 libraries is missing before Gradle runs,
or missing from the APK Gradle built.

## Versions

| | |
|---|---|
| FFmpeg | 6.1.6, tag `n6.1.6`, commit `f1e3a2bf7a2f2cde936d1ed97f09a26853d20125`, unmodified |
| Android NDK | r27d, `27.3.13750724` (sdkmanager package `ndk;27.3.13750724`) |
| API level | 26, the app's `androidMinSdk` |
| ABIs | armeabi-v7a, arm64-v8a, x86, x86_64 |
| media3 sources | androidx/media tag `1.11.0` |

The pins live in one place, the top of `scripts/build-ffmpeg-decoder.sh`; CI
reads them from there with `--print-pins`.

Why FFmpeg 6.1 and not the `release/6.0` that media3's README suggests: the
wrapper needs the `ch_layout` / `swr_alloc_set_opts2` API (FFmpeg 5.1 and
later), which both have, but 6.0 has had no tagged release since 6.0.1 in
November 2023, while 6.1.6 (June 2026) carries two and a half years more of
backported fixes. These decoders parse whatever file is played, so that
matters. Newer lines (7.1, 8.x) exist and are not used here.

## Licences

- The Java sources and `ffmpeg_jni.cc` are AndroidX Media's, under the Apache
  License 2.0; their headers are unchanged.
- FFmpeg is under the LGPL 2.1 or later as configured: no `--enable-gpl`, no
  `--enable-nonfree`, no `--enable-version3`. The script checks all three in
  `config.h`, and checks that each library reports
  `LGPL version 2.1 or later` (what `avcodec_license()` and friends return).
- FFmpeg is built as **shared** libraries (`libavcodec.so`, `libavutil.so`,
  `libswresample.so`) and `libffmpegJNI.so` loads them by name, so anyone can
  replace them with their own build — the point of the LGPL. A static build
  would fold FFmpeg into `libffmpegJNI.so`.
- `assets/licenses/ffmpeg/COPYING.LGPLv2.1` is FFmpeg's own file, byte for
  byte; the script refuses to build if it differs from the source it builds.
  `NOTICE.txt` names the version, the exact configure lines, the source
  locations and the replacement procedure, and carries the notices that some
  compiled files require in binary distributions:
  - the Independent JPEG Group statement (DCT code: `libavcodec/jfdctfst.c`,
    `libavcodec/jfdctint_template.c`, `libavcodec/jrevdct.c`);
  - MIT: musl's notice for `libavutil/avsscanf.c`, and the one for
    `libavcodec/arm/jrevdct_arm.S` (armeabi-v7a only);
  - ISC: `libavcodec/faandct.c`;
  - BSD: MIPS Technologies' 3-clause notice for `libavutil/fixed_dsp.c` and
    `fixed_dsp.h`, and Theodore Ts'o's for `libavutil/uuid.c`. These files
    carry FFmpeg's LGPL text as well: `fixed_dsp.*` in the same comment,
    `uuid.c` in a comment above the BSD one.

  Other licence text in compiled files needs nothing in the APK:
  `libavutil/adler32.c` (zlib licence, whose conditions cover source
  distributions), `libavutil/mathematics.c` and `libavutil/libm.h` (Boost
  1.0, which exempts object code; the Boost code in `libm.h` is an `erf`
  fallback compiled only where libm has no `erf`, and bionic has one) and
  `libavutil/sha.c` (derived from public-domain code).
- The script finds those files itself: it searches the full text of every
  source and header the compiler read (from the compiler's dependency files)
  and of the FFmpeg headers the wrapper is compiled against, for phrases of
  other licences (BSD, MIT, ISC, IJG, Boost, zlib, Apache, GPL, public domain,
  SPDX tags, ...). Each hit must be listed in the script, in `NOTICE_FILES`
  (and then named in `NOTICE.txt`) or in `NO_NOTICE_FILES`; anything else
  stops the build. It also refuses to build if `NOTICE.txt` does not name the
  pinned tag and commit.
- The LGPL also asks that the source of the library go along with the
  binaries, or be offered from the same place. `NOTICE.txt` points to
  FFmpeg's own copies of this exact release; DAView's releases do not carry a
  copy.

## How CI builds it

In the `build-android` job of `.github/workflows/build.yml`, before Gradle:

1. `scripts/build-ffmpeg-decoder.sh --print-pins` gives the FFmpeg tag and the
   NDK version.
2. `actions/cache/restore` looks for `build/ffmpeg-decoder/jniLibs`, keyed on
   the FFmpeg tag, the NDK version and the hash of the script, of
   `src/main/jni/`, of `src/main/java/` (whose native methods the script
   checks the exports against) and of `src/main/assets/licenses/` (which the
   script checks against FFmpeg's source and the compiled files). Changing
   any of those builds afresh, so none of the script's checks is skipped.
3. On a miss: `sdkmanager --install "ndk;27.3.13750724"`, then the script,
   which clones FFmpeg at the tag (shallow), checks the commit, builds all four
   ABIs and verifies them; then `actions/cache/save`.
4. A check that all 16 libraries are there, which fails the job otherwise. It
   prints their SHA-256.
5. After `assembleRelease`, `scripts/build-ffmpeg-decoder.sh --check-apk`
   on the unsigned APK: all 16 libraries under `lib/<abi>/`, each stored
   uncompressed on a 16 KB boundary (or compressed) with 16 KB-aligned
   segments, and both licence files byte for byte as in the repository. A
   build whose unsigned APK lacks any of that fails here, before signing.
   Signing happens in the `sign-android` job; this check does not look at
   the signed APK.

## Building locally

Any Linux x86_64 host with bash, make, git and gcc works; CMake and Ninja are
not needed. On a 16-core machine all four ABIs take about 40 seconds.

```sh
# The NDK the build is pinned to (or: sdkmanager --install "ndk;27.3.13750724").
curl -LO https://dl.google.com/android/repository/android-ndk-r27d-linux.zip
echo "22105e410cf29afcf163760cc95522b9fb981121  android-ndk-r27d-linux.zip" | sha1sum -c -
unzip -q android-ndk-r27d-linux.zip

# From the repository root. FFmpeg is cloned into the given directory if it is
# not there yet. The libraries land in build/ffmpeg-decoder/jniLibs/<abi>/.
bash scripts/build-ffmpeg-decoder.sh \
  --ndk "$PWD/android-ndk-r27d" \
  --ffmpeg ../ffmpeg-n6.1.6
```

Options: `--out DIR` (default `build/ffmpeg-decoder`; its `jniLibs` is
replaced on every run), `--jobs N`, `--work DIR` for the intermediate trees
(default `<out>/work`, safe to delete), `--allow-unpinned` to try another NDK
or FFmpeg. `--help` lists them.

`--abi x86_64` (repeatable) builds only the ABIs named. An APK built from such
an output installs only on those ABIs (`INSTALL_FAILED_NO_MATCHING_ABIS`
anywhere else), so unless `--out` says otherwise it goes to
`build/ffmpeg-decoder-partial`, not to the directory Gradle reads by default.
To use it, for example on the x86_64 emulator, build with
`-PdaviewFfmpegJniLibs=build/ffmpeg-decoder-partial/jniLibs`.

`--check-apk <apk>` checks an APK built from the output instead of building
(what CI runs after Gradle; needs only `python3`).

When the build ran on another machine, copy its `jniLibs` directory to
`build/ffmpeg-decoder/jniLibs` here, or point Gradle at it with
`-PdaviewFfmpegJniLibs=<dir>`. Gradle picks the libraries up on the next build.
A root-level `clean` task, if one is ever added, would delete them along with
the rest of `build/`.

The output should not depend on where the build runs: no absolute path reaches
the binaries (the script checks), and FFmpeg is built without debug info,
which would otherwise make lld's build ID hash the build directory. Two builds
of the same pins from different checkout paths on one machine produced
byte-identical libraries. CI's output is expected to match a local build as
well; its check step prints the SHA-256 of every library so that can be
confirmed.

What the script verifies before it writes anything (a failure stops it and
leaves the previous output in place):

- the NDK revision and the FFmpeg commit match the pins, and the FFmpeg tree is
  unmodified and was never configured in place;
- the configuration: no GPL / non-free / version-3 parts, shared and not
  static, threads on, no zlib/bzlib/lzma/iconv/Vulkan/V4L2/MediaCodec/JNI,
  exactly the five decoders (plus the `ac3` and `mlp` parsers those decoders
  select for their own header parsing);
- for each library: ELF class and machine, `SONAME` equal to the file name
  (FFmpeg's android target does not version it), dependencies limited to NDK
  system libraries and the libraries shipped alongside (`libc++_shared.so`
  would fail this), no text relocations, no `RPATH`/`RUNPATH`, every `LOAD`
  segment aligned to at least 16 KB (`-z max-page-size=16384`; NDK r27 does
  not do it by default), a non-executable stack, stripped, and no absolute
  path of the build machine anywhere in the file;
- the licence string each FFmpeg library reports;
- licence text other than the LGPL anywhere in the compiled sources and
  headers, against the two lists in the script and `NOTICE.txt` (see
  Licences above); every object file must have its dependency file, so no
  source escapes the search;
- that `libffmpegJNI.so` exports exactly `JNI_OnLoad` and the `Java_…`
  functions for every `native` method in the Java sources.

## Updating

- **media3**: copy the Java sources, `AndroidManifest.xml` and
  `jni/ffmpeg_jni.cc` from `libraries/decoder_ffmpeg/src/main/`, and
  `proguard-rules.txt`, from the new tag over the files here, unchanged (not
  upstream's `CMakeLists.txt` or `build_ffmpeg.sh`, which the script replaces).
  Read the diff of `ffmpeg_jni.cc` for FFmpeg API changes. The script derives
  the expected JNI exports from the Java sources, so a mismatch fails the
  build.
- **FFmpeg**: change the tag and commit at the top of the script, copy the new
  `COPYING.LGPLv2.1`, update the version and commit in `NOTICE.txt`, build,
  and rewrite the configure lines in `NOTICE.txt` from
  `<out>/build-info.txt`. If the build stops at the licence search, read the
  whole of each file it names (the BSD text in `fixed_dsp.c` and `uuid.c`
  sits under an LGPL header): where its licence asks binaries to carry its
  notice, copy the notice into `NOTICE.txt` and list the file in
  `NOTICE_FILES`, otherwise list it in `NO_NOTICE_FILES`. The script warns
  about listed files that are no longer compiled. The phrases it searches for
  are in `LICENCE_MARKERS`, and a licence worded in none of them would slip
  through, so when the version moves a long way, also read FFmpeg's
  `LICENSE.md` for files under other licences.
- **NDK**: change `NDK_VERSION` in the script and the download in this README.
  r28 and later align to 16 KB on their own; the flag stays harmless.
