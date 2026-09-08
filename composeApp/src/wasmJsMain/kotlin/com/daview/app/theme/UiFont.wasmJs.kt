package com.daview.app.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font

actual fun createFontFamily(bytes: ByteArray): FontFamily? =
    runCatching { FontFamily(Font("DAViewCJK", bytes)) }.getOrNull()

/**
 * Off by default: the download works and `FontFamily(Font(identity, bytes))`
 * builds without throwing, but Compose Multiplatform 1.12's wasm renderer still
 * draws CJK as tofu — with the family applied through `Typography`, through
 * `LocalTextStyle`, and set directly on a `Text`, with and without
 * `FontFamily.Resolver.preload`. Until that is resolved, fetching ~10 MB on
 * every cold load would buy nothing. Flip this to `true` to re-enable the path.
 */
actual val platformNeedsCjkFont: Boolean = false
