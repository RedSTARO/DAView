plugins {
    alias(libs.plugins.android.library)
}

/**
 * FFmpeg audio decoding for the Android player: DTS (including DTS-HD MA),
 * Dolby TrueHD, and AC-3 / E-AC-3 on phones without a Dolby decoder of their
 * own. Most phones have no MediaCodec decoder for the first two, and Blu-ray
 * rips and Matroska files carry them all the time.
 *
 * The Java half is media3's own FFmpeg extension, copied unchanged from
 * androidx/media 1.11.0 — the media3 version the app uses — because Google does
 * not publish it to Maven. It keeps the package androidx.media3.decoder.ffmpeg:
 * DefaultRenderersFactory looks FfmpegAudioRenderer up by that name, so the
 * player picks it up through the extension renderer mode alone.
 *
 * The native half is not built by Gradle. scripts/build-ffmpeg-decoder.sh
 * builds FFmpeg and the JNI wrapper on Linux (CI does it before assembling),
 * and this module only packages what that script wrote. Without those files
 * the module still compiles and the app still runs: FfmpegLibrary.isAvailable()
 * is false, FfmpegAudioRenderer reports every format unsupported, and audio the
 * phone cannot decode stays unplayable, as it was before. See README.md.
 */

/**
 * Where the script left the libraries, as `<abi>/lib*.so`. A path relative to
 * the repository root, or absolute: `-PdaviewFfmpegJniLibs=/elsewhere/jniLibs`.
 * The default is the script's own default output.
 */
val ffmpegJniLibs: File = (findProperty("daviewFfmpegJniLibs") as String?)
    ?.takeIf { it.isNotBlank() }
    ?.let { rootProject.file(it) }
    ?: rootProject.layout.projectDirectory.dir("build/ffmpeg-decoder/jniLibs").asFile

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        // Upstream's rules: native method names and the growOutputBuffer
        // callback must survive shrinking. The app does not shrink today.
        consumerProguardFiles("proguard-rules.txt")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("main") {
            // A directory that may not exist; AGP then packages nothing from it.
            jniLibs.srcDir(ffmpegJniLibs)
        }
    }
}

dependencies {
    api(libs.androidx.media3.decoder)
    // FfmpegAudioRenderer extends DecoderAudioRenderer and builds a
    // DefaultAudioSink, both in the ExoPlayer module.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.annotation)
    // @MonotonicNonNull in FfmpegLibrary. Guava used to bring checker-qual onto
    // the compile classpath and stopped doing so in 33.5.
    compileOnly(libs.checkerframework.qual)
}
