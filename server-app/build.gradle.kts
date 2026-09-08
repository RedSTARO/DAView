import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":server"))
    implementation(libs.logback.classic)
}

application {
    mainClass.set("com.daview.server.ServerMainKt")
}

/**
 * `gradle run` executes from the module directory, where the relative default
 * path to the built web client does not resolve. Point both at the repository
 * root so a dev run serves the web UI the same way the installed distribution
 * does from there.
 */
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    environment(
        "DAVIEW_WEB_DIR",
        rootProject.layout.projectDirectory
            .dir("composeApp/build/dist/wasmJs/productionExecutable").asFile.absolutePath
    )
}
