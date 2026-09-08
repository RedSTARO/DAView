import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

/**
 * The scanning, scraping, database and HTTP core, built for both the JVM and
 * Android so the phone can run it in-process instead of talking to a separate
 * server.
 *
 * Both targets compile the same sources out of `src/main/kotlin` rather than a
 * shared intermediate source set. Everything in there is plain JVM bytecode
 * that both platforms support, and the one genuine difference — the SQL driver —
 * is injected into `ServerContext` rather than resolved through expect/actual,
 * so there is nothing an intermediate source set would buy.
 */
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        // Deliberately not src/main/kotlin: with the legacy Android DSL that
        // directory is also AGP's own main source set for the library, so
        // registering it again left compileDebugKotlinAndroid reporting
        // UP-TO-DATE after edits and shipping stale server code in the APK.
        val core = "src/core"

        jvmMain {
            kotlin.srcDir(core)
            kotlin.srcDir("src/jvmOnly/kotlin")
            dependencies {
                implementation(libs.sqlite.jdbc)
                implementation(libs.logback.classic)
            }
        }

        androidMain {
            kotlin.srcDir(core)
            dependencies {
                // Typed binding for both statements and queries, which
                // SQLiteDatabase.rawQuery's String[] arguments cannot express.
                implementation(libs.androidx.sqlite.framework)
                // slf4j-android is a 1.7-era binding that SLF4J 2 ignores, which
                // left the server silent on the phone. slf4j-simple is a real 2.x
                // provider and its stderr output lands in logcat.
                implementation(libs.slf4j.simple)
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
        val shared = listOf(jvmMain.get(), androidMain.get())
        shared.forEach { sourceSet ->
            sourceSet.dependencies {
                api(project(":shared"))

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)

                // Exposed so the desktop and Android clients can host the server
                // in-process.
                api(libs.ktor.server.core)
                api(libs.ktor.server.cio)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.server.cors)
                implementation(libs.ktor.server.status.pages)
                implementation(libs.ktor.server.call.logging)
                implementation(libs.ktor.server.compression)
                implementation(libs.ktor.server.partial.content)
                implementation(libs.ktor.serialization.json)

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.content.negotiation)

                // Blocking HTTP that also exists on Android, unlike java.net.http.
                implementation(libs.okhttp)
            }
        }
    }
}

android {
    namespace = "com.daview.server"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
