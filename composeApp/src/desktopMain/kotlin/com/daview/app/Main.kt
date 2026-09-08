package com.daview.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlin.system.exitProcess

/**
 * Desktop entry point.
 *
 * Nothing is started here any more. The library is created with the UI and
 * lives in this process; the only socket the app ever opens is the playback
 * pipe, and only while an external player is reading from it.
 */
fun main() {
    application {
        Window(
            onCloseRequest = {
                exitApplication()
                exitProcess(0)
            },
            title = "DAView",
            state = rememberWindowState(size = DpSize(1360.dp, 900.dp))
        ) {
            App()
        }
    }
}
