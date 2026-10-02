package com.daview.app.platform

import java.awt.Desktop
import java.io.File
import java.util.Base64
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
 * run by a small script that starts after the app has left: it waits a
 * moment, runs the installer with a progress bar and no questions, then starts
 * the app again from where it was installed. `msiexec` is not started directly
 * because there would be nothing left to start the app afterwards.
 */
private fun installOnWindows(installer: File): InstallOutcome {
    // The running launcher, when this is a packaged build. Under `gradle run`
    // it is java.exe, which is not what to bring back.
    val launcher = ProcessHandle.current().info().command().orElse(null)
        ?.takeIf { it.endsWith("DAView.exe", ignoreCase = true) }
    return runCatching {
        ProcessBuilder(windowsInstallCommand(installer.absolutePath, launcher))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .outputStream.close()
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
 * The command that runs [windowsInstallScript]: Windows PowerShell, minimised
 * by `start` and then hidden, with the script handed over encoded.
 *
 * This was a batch file, started with `cmd /c start "" "<path>"`, and a batch
 * file's own path goes through cmd's parser on the way in. `start` runs it as
 * `cmd /k "<path>"`, and cmd drops those quotes whenever the path holds one of
 * `&<>()@^|` — a profile directory called `R&D` was enough — so the script
 * never ran: no installer, no app, and a console left open saying
 * `'C:\Users\R' is not recognized`. `call` keeps the quotes but doubles every
 * `^` in them. Encoded, no path is on any command line at all: the paths
 * travel inside the script as PowerShell literals, and the line cmd sees is
 * this executable plus base64.
 */
internal fun windowsInstallCommand(
    installer: String,
    relaunch: String?,
    msiexec: String = systemExecutable("msiexec.exe")
): List<String> {
    val script = windowsInstallScript(installer, relaunch, msiexec)
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    return listOf(
        "cmd", "/c", "start", "\"\"", "/min",
        systemExecutable("WindowsPowerShell\\v1.0\\powershell.exe"),
        "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
        "-EncodedCommand", encoded
    )
}

/**
 * The script itself. The installer's failure — a refused elevation prompt, a
 * package Windows will not open — still brings the app back, as it was.
 */
internal fun windowsInstallScript(installer: String, relaunch: String?, msiexec: String): String = buildString {
    appendLine("Start-Sleep -Seconds 2")
    appendLine("\$installer = ${powerShellLiteral(installer)}")
    appendLine(
        "try { Start-Process -FilePath ${powerShellLiteral(msiexec)} " +
            "-ArgumentList @('/i', ('\"' + \$installer + '\"'), '/passive') -Wait } catch { }"
    )
    if (relaunch != null) {
        appendLine("\$app = ${powerShellLiteral(relaunch)}")
        appendLine("if (Test-Path -LiteralPath \$app) { Start-Process -FilePath \$app }")
    }
}

/**
 * [text] as a PowerShell single-quoted string. Nothing is special inside one
 * except the quote itself, which is doubled — and PowerShell takes the
 * typographic single quotes as that quote too, so `John’s PC` needs the same.
 */
internal fun powerShellLiteral(text: String): String =
    "'" + text.replace(Regex("['\u2018\u2019\u201A\u201B]")) { it.value + it.value } + "'"

/** A program in System32, by its full path rather than whatever PATH finds first. */
private fun systemExecutable(relative: String): String {
    val root = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
    return "$root\\System32\\$relative"
}
