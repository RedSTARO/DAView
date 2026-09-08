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
                // The in-app player is libmpv, reached through JNA. Not FFM:
                // both JVM targets compile at language level 17, and the panama
                // API only became final in 22.
                implementation(libs.jna)
            }
        }

        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
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

// The packaging tasks treat `appResourcesRootDir` as an internal property, not
// an input, so dropping libmpv into it after a build leaves them up to date and
// they happily re-emit a package with an empty `app/resources`. Verified: fetch
// the DLL, build again, and it is in the staging directory but not in the
// package. Declaring the directory as an input is what makes the second build
// notice. Optional, because a checkout that never fetches has no such directory.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    inputs.dir(project.layout.projectDirectory.dir("nativeResources"))
        .withPropertyName("daviewAppResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional(true)
}

compose.desktop {
    application {
        mainClass = "com.daview.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "DAView"
            packageVersion = "1.0.0"
            // Everything under here is copied into the installed app next to
            // the runtime, and found at run time through the
            // `compose.application.resources.dir` system property. The Windows
            // subdirectory holds libmpv-2.dll — the in-app player. It is ~115 MB
            // and is therefore not in git: `scripts/fetch-libmpv.*` puts it
            // there, and CI runs that before packaging. A build without it still
            // packages fine; the desktop app just falls back to external players.
            //
            // prepareAppResources only copies the subdirectory matching the
            // machine doing the build, so the Linux and macOS packages never
            // carry the Windows DLL.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("nativeResources"))
            // jlink builds the bundled runtime from this list plus what Compose
            // asks for, and anything missing only shows up at runtime: the
            // Windows launcher swallows the stack trace and reports "Failed to
            // launch JVM". Both entries are reached on the very first frame —
            // sqlite-jdbc needs java.sql, and logback's XML configurator hard
            // references JNDI, so it needs java.naming even though nothing here
            // uses a JNDI lookup.
            modules("java.sql", "java.naming")
        }
    }
}
