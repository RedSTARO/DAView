package com.daview.app.theme

import androidx.compose.ui.text.font.FontFamily

/** The platform font manager already covers CJK here. */
actual fun createFontFamily(bytes: ByteArray): FontFamily? = null

actual val platformNeedsCjkFont: Boolean = false
