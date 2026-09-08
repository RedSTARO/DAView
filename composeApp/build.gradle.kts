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
        // The UI talks to :core directly, and :core is a JVM library — its
        // sources are compiled per target, so there is no common metadata for
        // commonMain to see. Registering one directory in both JVM targets
        // gives the whole client access to it without inventing an interface
        // whose only job would be to have two identical implementations.
        // commonMain keeps just the expect declarations.
        val app = "src/app"

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":shared"))
        }

        androidMain {
            kotlin.srcDir(app)
            dependencies {
                implementation(project(":core"))
                implementation(compose.preview)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.media3.exoplayer)
                implementation(libs.androidx.media3.session)
                implementation(libs.androidx.media3.ui)
            }
        }

        val desktopMain by getting
        desktopMain.apply {
            kotlin.srcDir(app)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(project(":core"))
            }
        }

        // Needed by the shared source directory, which is compiled into both
        // JVM targets rather than into commonMain.
        listOf(androidMain.get(), desktopMain).forEach { sourceSet ->
            sourceSet.dependencies {
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.jetbrains.lifecycle.viewmodel.compose)
                implementation(libs.coil.compose)
            }
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
