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

        // The ASS renderer draws with android.graphics, so the only place its
        // output can be checked is on a device. These need one attached and are
        // not part of CI: `./gradlew :composeApp:connectedDebugAndroidTest`.
        val androidInstrumentedTest by getting
        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
// notice.
//
// A file tree rather than `inputs.dir`, because the directory is often not
// there at all: its contents are fetched, not committed, and git cannot carry
// an empty directory. `optional(true)` does not cover that — it says the
// property may have no value, not that a named directory may be missing, so
// `inputs.dir` failed the Linux and macOS builds at configuration time while
// Windows passed only because the fetch step had just created it. A tree of a
// missing directory is simply empty.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    inputs.files(project.fileTree("nativeResources"))
        .withPropertyName("daviewAppResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/**
 * The version the installers carry, which is not the human label CI puts in the
 * file name.
 *
 * It has to move with the build or Windows refuses to install one package over
 * another: jpackage mints a fresh ProductCode every time, and a package whose
 * ProductVersion equals the installed one is neither an upgrade nor a
 * downgrade. Windows Installer answers that with 1638 — "已经安装了该产品的另一
 * 个版本" — and the only way on is to uninstall by hand. It sat at 1.0.0 for
 * every build ever made, so every update did exactly that.
 *
 * MSI compares only the first three fields, and each has a ceiling, so this is
 * checked rather than passed through: jpackage's own complaint arrives late and
 * says nothing about which field was wrong.
 *
 * In PowerShell the whole argument has to be quoted — `"-PdaviewPackageVersion=
 * 1.0.85"` — because PowerShell splits an unquoted one at the first dot, in the
 * name or in the value alike, and passes the remainder on as a separate argument
 * that Gradle then reads as a task name. Git Bash passes the same string through
 * untouched, so it works unquoted there and nowhere says why it did not on
 * Windows. The check below is what turns that into a sentence rather than a
 * package quietly built as version "1".
 */
val daviewPackageVersion: String = (findProperty("daviewPackageVersion") as String?)
    ?.takeIf { it.isNotBlank() }
    ?.also { version ->
        val parts = version.split('.')
        require(parts.size == 3) { "daviewPackageVersion must be MAJOR.MINOR.PATCH, was '$version'" }
        val limits = listOf(255, 255, 65535)
        parts.forEachIndexed { index, part ->
            val value = part.toIntOrNull()
            require(value != null && value in 0..limits[index]) {
                "daviewPackageVersion field ${index + 1} must be 0..${limits[index]}, was '$part'"
            }
        }
    }
    ?: defaultPackageVersion()

/**
 * What a build that was not told a version carries.
 *
 * Not a constant, because a constant is what caused this: every package ever
 * built said 1.0.0, so none of them could be installed over another. The patch
 * is the commit count, the same number CI uses, so a package built by hand
 * still replaces the one before it. Zero only when git cannot be reached, and
 * a package built outside a checkout is not one anybody upgrades to.
 */
fun defaultPackageVersion(): String {
    val commits = runCatching {
        ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText().trim().toIntOrNull()
    }.getOrNull()
    return "1.0.${commits ?: 0}"
}

compose.desktop {
    application {
        mainClass = "com.daview.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "DAView"
            // Named apart from the property it feeds, deliberately: inside this
            // block `packageVersion` is the block's own, so a val sharing the
            // name assigns the default to itself and the build says nothing at
            // all — it just keeps shipping 1.0.0.
            packageVersion = daviewPackageVersion

            windows {
                // Pinned rather than left to jpackage. What it derives is
                // stable — a name-based (version 3) UUID, and this is the value
                // it produces for "DAView", so copies already installed are
                // recognised — but it is derived from the package name, and
                // renaming the app would silently orphan every one of them:
                // the new package would not see the old install to replace, and
                // both would sit in Add/Remove Programs forever.
                upgradeUuid = "07822F86-3B17-30C5-8A30-3D898F138AE3"
            }
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
