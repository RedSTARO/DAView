import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

// The Android half of :composeApp. Applied by build.gradle.kts only when the build
// does Android (settings.gradle.kts).
//
// A script plugin is compiled against its own buildscript class path, not the
// project's, so the Android plugin is named here as well. These are the jars
// the root build script already put on the parent class loader, and that is
// where they are loaded from, so the extension types here are the ones the
// applied plugin registered. Script plugins also do not get the `libs`
// accessor, hence the catalog lookup.
buildscript {
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
    dependencies {
        classpath((gradle as ExtensionAware).extra["daviewAgp"] as String)
    }
}

val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
// Computed by build.gradle.kts and handed over through extra properties.
val daviewPackageVersion = extra["daviewPackageVersion"] as String
val daviewVersionLabel = extra["daviewVersionLabel"] as String

configure<ApplicationExtension> {
    namespace = "com.daview.app"
    compileSdk = libs.findVersion("androidCompileSdk").get().requiredVersion.toInt()

    defaultConfig {
        applicationId = "com.daview.app"
        minSdk = libs.findVersion("androidMinSdk").get().requiredVersion.toInt()
        targetSdk = libs.findVersion("androidTargetSdk").get().requiredVersion.toInt()
        // The patch field is the commit count — see daviewPackageVersion — so
        // every build's code is above the one before, which is what the package
        // installer needs before it accepts an update.
        versionCode = daviewPackageVersion.substringAfterLast('.').toIntOrNull()?.coerceAtLeast(1) ?: 1
        versionName = daviewVersionLabel
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Build and tooling metadata nothing reads at run time. The coroutines
        // debug probes are for an attached debugger, which a release has none of.
        resources.excludes += listOf(
            "/META-INF/*.version",
            "/META-INF/*.kotlin_module",
            "/kotlin-tooling-metadata.json",
            "DebugProbesKt.bin"
        )
        // Native libraries compressed in the APK rather than stored for
        // mapping in place: about half the size to download, extracted once at
        // install. Every library is still built with 16 KB segment alignment,
        // which is what 16 KB-page devices need of an extracted library.
        jniLibs.useLegacyPackaging = true
    }
    buildTypes {
        getByName("release") {
            // Shrinking, optimisation and obfuscation. The APK was 24 MB, 17 of
            // them dex — most of it Material's extended icon set and library
            // code the app never calls. The rules that keep what is reached by
            // name are in proguard-android.pro; the mapping file that turns an
            // obfuscated stack trace back into names is kept by CI.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-android.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
