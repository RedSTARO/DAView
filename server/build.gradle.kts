import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

/**
 * The HTTP face of `:core`: routes, the access token, and hosting the web
 * client's static files.
 *
 * It is one front end, not the way in. The desktop and Android apps call
 * `:core` directly and do not depend on this module, which is why neither of
 * them opens a port to talk to itself; what needs this is the web client,
 * whose browser cannot reach a WebDAV share or a SQLite file on its own.
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
        // See :core — src/main/kotlin collides with AGP's own source set under
        // the legacy Android DSL and leaves stale classes in the APK.
        val core = "src/core"

        jvmMain { kotlin.srcDir(core) }
        androidMain { kotlin.srcDir(core) }

        val shared = listOf(jvmMain.get(), androidMain.get())
        shared.forEach { sourceSet ->
            sourceSet.dependencies {
                api(project(":core"))

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)

                api(libs.ktor.server.core)
                api(libs.ktor.server.cio)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.server.cors)
                implementation(libs.ktor.server.status.pages)
                implementation(libs.ktor.server.call.logging)
                implementation(libs.ktor.server.compression)
                implementation(libs.ktor.server.partial.content)
                implementation(libs.ktor.serialization.json)
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
