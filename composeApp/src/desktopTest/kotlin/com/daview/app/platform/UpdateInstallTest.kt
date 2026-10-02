package com.daview.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The script has to run after the app is gone, install, and bring the app back. */
class UpdateInstallTest {

    @Test
    fun `the windows script waits, installs, then relaunches the app`() {
        val script = windowsInstallScript(
            "C:\\Users\\某人\\AppData\\Local\\DAView\\updates\\DAView-v1.2.0.msi",
            "C:\\Program Files\\DAView\\DAView.exe"
        )
        val lines = script.trim().lines()
        assertEquals("@echo off", lines[0])
        assertEquals("chcp 65001 >nul", lines[1], "the paths are UTF-8")
        assertTrue(lines[2].startsWith("timeout /t 2"), "the app is still leaving when the script starts")
        assertEquals(
            "msiexec /i \"C:\\Users\\某人\\AppData\\Local\\DAView\\updates\\DAView-v1.2.0.msi\" /passive",
            lines[3]
        )
        assertEquals(
            "if exist \"C:\\Program Files\\DAView\\DAView.exe\" start \"\" \"C:\\Program Files\\DAView\\DAView.exe\"",
            lines[4]
        )
        // `start` runs a batch file under `cmd /k`; without this its console
        // stays open, minimised, after every update.
        assertEquals("exit", lines.last())
    }

    @Test
    fun `without a known launcher nothing is relaunched`() {
        val script = windowsInstallScript("C:\\x\\DAView.msi", null)
        assertFalse("start" in script)
        assertTrue("msiexec" in script)
        assertEquals("exit", script.trim().lines().last())
    }

    @Test
    fun `a percent sign in a path is spelled the way a batch file needs it`() {
        val script = windowsInstallScript("C:\\Users\\50%off\\updates\\DAView.msi", null)
        assertTrue("msiexec /i \"C:\\Users\\50%%off\\updates\\DAView.msi\" /passive" in script, script)
    }
}
