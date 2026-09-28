package com.daview.app.platform

import java.awt.Desktop
import java.io.File
import kotlin.system.exitProcess

actual fun updateAssetKey(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        "win" in os -> "windows"
        "mac" in os || "darwin" in os -> "macos"
        else -> "linux"
    }
}

/** Any packaged desktop build can be replaced by the next package; a `gradle run` can too, harmlessly. */
actual fun canSelfUpdate(): Boolean = true

actual fun installUpdate(file: String): InstallOutcome {
    val installer = File(file)
    if (!installer.isFile) return InstallOutcome.FAILED
    return if (updateAssetKey() == "windows") installOnWindows(installer) else openWithSystem(installer)
}

/**
 * Windows Installer cannot replace files the app holds open, so the MSI is
 * run by a batch file that starts after the app has left: the script waits a
 * moment, runs the installer with a progress bar and no questions, then starts
 * the app again from where it was installed. `msiexec` is not started directly
 * because there would be nothing left to start the app afterwards.
 */
private fun installOnWindows(installer: File): InstallOutcome {
    // The running launcher, when this is a packaged build. Under `gradle run`
    // it is java.exe, which is not what to bring back.
    val launcher = ProcessHandle.current().info().command().orElse(null)
        ?.takeIf { it.endsWith("DAView.exe", ignoreCase = true) }
    val script = File(installer.parentFile, "install-update.cmd")
    return runCatching {
        script.writeText(windowsInstallScript(installer.absolutePath, launcher), Charsets.UTF_8)
        ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", script.absolutePath).start()
        // Off a daemon thread so the page can say what is happening first.
        Thread {
            Thread.sleep(800)
            exitProcess(0)
        }.apply { isDaemon = true }.start()
        InstallOutcome.EXITING
    }.getOrElse { InstallOutcome.FAILED }
}

/** A .dmg mounts and a .deb opens in the package installer; the person finishes it from there. */
private fun openWithSystem(installer: File): InstallOutcome = runCatching {
    Desktop.getDesktop().open(installer)
    InstallOutcome.HANDED_OVER
}.getOrElse { InstallOutcome.FAILED }

/**
 * The batch file [installOnWindows] runs. `chcp 65001` first, because the
 * file is written as UTF-8 and the paths in it may well not be ASCII — the
 * updates directory sits under the user's profile.
 */
internal fun windowsInstallScript(installer: String, relaunch: String?): String = buildString {
    appendLine("@echo off")
    appendLine("chcp 65001 >nul")
    appendLine("timeout /t 2 /nobreak >nul")
    appendLine("msiexec /i \"$installer\" /passive")
    if (relaunch != null) appendLine("if exist \"$relaunch\" start \"\" \"$relaunch\"")
}
