package com.daview.app.platform

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The script has to run after the app is gone, install, and bring the app back — whatever the paths hold. */
class UpdateInstallTest {

    private val msi = "C:\\Users\\R&D 50%off ^x! 测试\\AppData\\Local\\DAView\\updates\\DAView-v1.2.0.msi"
    private val exe = "C:\\Program Files\\DAView\\DAView.exe"

    @Test
    fun `the script waits, installs, then relaunches the app`() {
        val lines = windowsInstallScript(msi, exe, "C:\\Windows\\System32\\msiexec.exe").trim().lines()
        assertEquals("Start-Sleep -Seconds 2", lines[0], "the app is still leaving when the script starts")
        assertEquals("\$installer = '$msi'", lines[1], "nothing in the path needs escaping in a single-quoted literal")
        assertTrue(lines[2].startsWith("try { Start-Process -FilePath 'C:\\Windows\\System32\\msiexec.exe'"), lines[2])
        assertTrue("@('/i', ('\"' + \$installer + '\"'), '/passive') -Wait" in lines[2], lines[2])
        assertEquals("\$app = '$exe'", lines[3])
        assertEquals("if (Test-Path -LiteralPath \$app) { Start-Process -FilePath \$app }", lines[4])
    }

    @Test
    fun `without a known launcher nothing is relaunched`() {
        val script = windowsInstallScript("C:\\x\\DAView.msi", null, "msiexec.exe")
        assertFalse("\$app" in script)
        assertTrue("msiexec.exe" in script)
    }

    @Test
    fun `every kind of single quote in a path is doubled`() {
        assertEquals("'John''s PC'", powerShellLiteral("John's PC"))
        assertEquals("'John\u2019\u2019s PC'", powerShellLiteral("John\u2019s PC"))
        assertEquals("'a&b^c%d!e'", powerShellLiteral("a&b^c%d!e"))
    }

    @Test
    fun `no path reaches a command line, only the encoded script does`() {
        val command = windowsInstallCommand(msi, exe, "msiexec.exe")
        assertEquals(listOf("cmd", "/c", "start", "\"\"", "/min"), command.take(5))
        assertTrue(command[5].endsWith("\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"), command[5])
        assertEquals("-EncodedCommand", command[command.size - 2])
        command.dropLast(1).forEach { argument ->
            assertFalse("R&D" in argument || "测试" in argument, "a path leaked onto the command line: $argument")
        }
        val decoded = String(Base64.getDecoder().decode(command.last()), Charsets.UTF_16LE)
        assertEquals(windowsInstallScript(msi, exe, "msiexec.exe"), decoded)
    }
}
