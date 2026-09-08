package com.daview.server.media

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * Finds a CJK-capable font on the host so the web client can render Chinese.
 *
 * Plain `.ttf` files are preferred over `.ttc` collections: Skia can only load
 * the first face of a collection, and several Windows collections start with a
 * face that is not the one we want.
 */
object SystemFonts {

    private val candidates: List<String> = listOf(
        // Windows
        "C:/Windows/Fonts/simhei.ttf",
        "C:/Windows/Fonts/Deng.ttf",
        "C:/Windows/Fonts/simkai.ttf",
        "C:/Windows/Fonts/msyh.ttc",
        // Linux
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc",
        "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
        "/usr/share/fonts/truetype/arphic/uming.ttc",
        // macOS
        "/System/Library/Fonts/PingFang.ttc",
        "/System/Library/Fonts/STHeiti Light.ttc"
    )

    @Volatile
    private var cached: Path? = null

    fun cjkFont(): Path? {
        cached?.let { return it }
        val override = System.getenv("DAVIEW_CJK_FONT")?.let { Path.of(it) }
        val found = (listOfNotNull(override) + candidates.map { Path.of(it) })
            .firstOrNull { it.exists() && it.isRegularFile() }
        cached = found
        return found
    }
}
