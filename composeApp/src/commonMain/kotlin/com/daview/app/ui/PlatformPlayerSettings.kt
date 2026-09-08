package com.daview.app.ui

import androidx.compose.runtime.Composable

/**
 * Settings that exist on one platform only.
 *
 * Desktop has an embedded engine to point at and NVIDIA switches to flip;
 * Android's player has neither, so its side of this is empty rather than a
 * section full of controls that would do nothing.
 */
@Composable
expect fun PlatformPlayerSettings()
