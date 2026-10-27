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

/**
 * Scanning, scraping, the database, playback bookkeeping and cross-device sync
 * — everything the app does that is not drawing, built for both the JVM and
 * Android so each app runs it in its own process.
 *
 * There is deliberately no HTTP server in here. The desktop and Android apps
 * call in directly, which is why neither of them has to open a port to talk to
 * itself; the web client that needed one, and the `:server` module that served
 * it, have been removed.
 *
 * Both targets compile the same sources out of `src/core` rather than a shared
 * intermediate source set. Everything in there is plain JVM bytecode that both
 * platforms support, and the one genuine difference — the SQL driver — is
 * injected into `ServerContext` rather than resolved through expect/actual, so
 * there is nothing an intermediate source set would buy.
 */
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    if (withAndroid) {
        androidTarget {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }

    sourceSets {
        // Deliberately not src/main/kotlin: with the legacy Android DSL that
        // directory is also AGP's own main source set for the library, so
        // registering it again left compileDebugKotlinAndroid reporting
        // UP-TO-DATE after edits and shipping stale code in the APK.
        val core = "src/core"

        jvmMain {
            kotlin.srcDir(core)
            kotlin.srcDir("src/jvmOnly/kotlin")
            dependencies {
                implementation(libs.sqlite.jdbc)
                implementation(libs.logback.classic)
            }
        }

        if (withAndroid) {
            androidMain {
                kotlin.srcDir(core)
                dependencies {
                    // API 26's platform SQLite predates the shared UPSERT queries.
                    implementation(libs.androidx.sqlite.bundled)
                    // slf4j-android is a 1.7-era binding that SLF4J 2 ignores, which
                    // left the server silent on the phone. slf4j-simple is a real 2.x
                    // provider and its stderr output lands in logcat.
                    implementation(libs.slf4j.simple)
                }
            }
        }

        jvmTest {
            kotlin.srcDir("src/jvmTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.sqlite.jdbc)
            }
        }

        // Shared by both targets. These are all multiplatform artifacts or plain
        // JVM libraries that Android also ships.
        val shared = listOfNotNull(jvmMain.get(), if (withAndroid) androidMain.get() else null)
        shared.forEach { sourceSet ->
            sourceSet.dependencies {
                api(project(":shared"))

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)

                // The only HTTP client here, and it is an outbound one: WebDAV,
                // the scrapers and the artwork cache. Blocking, and it exists on
                // Android, unlike java.net.http.
                implementation(libs.okhttp)
            }
        }
    }
}

if (withAndroid) apply(from = "android.gradle")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
