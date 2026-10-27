// The Android Gradle plugin is deliberately not in the plugins {} block below:
// that block resolves every plugin it names, applied or not, and the Android
// side of this build is optional (settings.gradle.kts decides). The plugin
// goes on the build script class path instead, only when Android is being
// built, and the modules then apply it by id.
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
        if ((gradle as ExtensionAware).extra["daviewAndroid"] as Boolean) {
            classpath((gradle as ExtensionAware).extra["daviewAgp"] as String)
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}
