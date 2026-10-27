import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

// The Android half of :shared. Applied by build.gradle.kts only when the build
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

configure<LibraryExtension> {
    namespace = "com.daview.shared"
    compileSdk = libs.findVersion("androidCompileSdk").get().requiredVersion.toInt()
    defaultConfig {
        minSdk = libs.findVersion("androidMinSdk").get().requiredVersion.toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
