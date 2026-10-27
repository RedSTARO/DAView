import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Android is optional in this build (settings.gradle.kts). When it is on, the
// plugin is already on the class path, and the Android half of the module's
// configuration lives in android.gradle (a Groovy script: see the note there)
// so that this script compiles without the plugin's classes.
val withAndroid = (gradle as ExtensionAware).extra["daviewAndroid"] as Boolean
if (withAndroid) apply(plugin = "com.android.library")

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    if (withAndroid) {
        androidTarget {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
    }
}

if (withAndroid) apply(from = "android.gradle")
