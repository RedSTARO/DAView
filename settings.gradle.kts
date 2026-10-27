rootProject.name = "DAView"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com[.]android.*")
                includeGroupByRegex("com[.]google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com[.]android.*")
                includeGroupByRegex("com[.]google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

// Whether this build does Android at all.
//
// Every module but :ffmpeg-decoder also builds for the JVM, and neither the
// desktop app nor the core tests need anything from Android — yet the Android
// Gradle plugin was applied unconditionally, so a machine without an Android
// SDK could not even configure the build, let alone run `:core:jvmTest`. The
// Android plugin and targets are now only set up when an SDK can be found
// (ANDROID_HOME, ANDROID_SDK_ROOT, or sdk.dir in local.properties) or when
// `-PdaviewAndroid=true` insists; `-PdaviewAndroid=false` leaves Android out
// even with an SDK around. The modules read the decision from
// `gradle.extra["daviewAndroid"]`, and the root build script puts the Android
// plugin on the class path only when it is true.
val withAndroid: Boolean = run {
    val explicit = providers.gradleProperty("daviewAndroid").orNull?.trim()
    if (!explicit.isNullOrEmpty()) return@run explicit.toBoolean()
    val fromLocalProperties = rootDir.resolve("local.properties")
        .takeIf { it.isFile }
        ?.let { file ->
            java.util.Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir")
        }
    listOfNotNull(fromLocalProperties, System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
        .any { it.isNotBlank() && java.io.File(it).isDirectory }
}
(gradle as ExtensionAware).extra["daviewAndroid"] = withAndroid

// The Android Gradle plugin's coordinates, which the root build script puts
// on its class path in that case (settings cannot use the `libs` accessor,
// so the version is read from the catalog file directly).
val agpVersion = Regex("""^agp\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
    .find(rootDir.resolve("gradle/libs.versions.toml").readText())
    ?.groupValues?.get(1)
    ?: error("gradle/libs.versions.toml has no agp version")
(gradle as ExtensionAware).extra["daviewAgp"] = "com.android.tools.build:gradle:$agpVersion"
if (!withAndroid) {
    logger.lifecycle(
        "No Android SDK found: configuring the JVM targets only. " +
            "Set ANDROID_HOME (or -PdaviewAndroid=true) to build the Android app."
    )
}

include(":shared")
include(":core")
include(":composeApp")
if (withAndroid) include(":ffmpeg-decoder")
