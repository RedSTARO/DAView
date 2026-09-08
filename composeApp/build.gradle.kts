import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.application)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }


    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }

        androidMain.dependencies {
            // The phone runs the whole core itself; there is no separate backend.
            implementation(project(":server"))
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.session)
            implementation(libs.androidx.media3.ui)
        }

        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.cio)
            implementation(project(":server"))
        }

    }
}

android {
    namespace = "com.daview.app"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.daview.app"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "com.daview.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "DAView"
            packageVersion = "1.0.0"
        }
    }
}
