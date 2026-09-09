package com.daview.app.subtitle

import android.content.Context
import android.graphics.Typeface
import java.io.File
import java.io.RandomAccessFile

/**
 * Finds a typeface for the family names an ASS script asks for.
 *
 * Every script in this library names fonts that no Android device has —
 * 方正兰亭圆_GBK_中粗, A-OTF Shin Maru Go Pro DB, FOT-Seurat Pro DB and so on,
 * shipped alongside the video as a font archive for the desktop players to
 * install. Two things follow. Fonts dropped into the app's own font directory
 * are used by their real family name, which is how a viewer who copies that
 * archive over gets what the typesetter intended; and anything still missing
 * falls back by what the name says about the face rather than to whatever
 * `Typeface.create` hands back for an unknown family, which is the default
 * sans and loses the distinction between a script face and a Gothic one.
 */
class AssFonts(private val userFontDir: File?) {

    private val cache = HashMap<String, Typeface?>()
    private val userFonts: Map<String, File> by lazy { scanUserFonts() }

    fun typeface(family: String, bold: Boolean, italic: Boolean): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val key = "$family|$style"
        cache[key]?.let { return it }
        val resolved = resolve(family, style)
        cache[key] = resolved
        return resolved
    }

    private fun resolve(family: String, style: Int): Typeface {
        val name = family.trim().removePrefix("@")

        userFonts[name.lowercase()]?.let { file ->
            runCatching { Typeface.createFromFile(file) }.getOrNull()?.let {
                return if (style == Typeface.NORMAL) it else Typeface.create(it, style)
            }
        }

        val installed = Typeface.create(name, style)
        // `Typeface.create` answers with the default for a family it does not
        // have, and says nothing about which happened, so the only way to know
        // is to ask what the default is and compare.
        if (installed != Typeface.create(Typeface.DEFAULT, style)) return installed

        return Typeface.create(fallbackFamily(name), style)
    }

    /**
     * What to use when the named face is not on the device.
     *
     * The names carry their own classification: a font whose name says 明朝,
     * 明體, 宋 or Mincho is a serif, one that says 黑, ゴシック, Gothic or Hei is
     * a sans, and the rounded and brush faces have no counterpart at all, so
     * they take the nearest of the two.
     */
    private fun fallbackFamily(name: String): String {
        val lower = name.lowercase()
        val serif = listOf(
            "mincho", "ming", "song", "serif", "roman", "georgia", "times",
            "明朝", "明体", "明體", "宋", "楷", "kai", "隶", "隸", "魏", "書", "书"
        )
        return if (serif.any { lower.contains(it) }) "serif" else "sans-serif"
    }

    private fun scanUserFonts(): Map<String, File> {
        val dir = userFontDir?.takeIf { it.isDirectory } ?: return emptyMap()
        val found = HashMap<String, File>()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val extension = file.extension.lowercase()
            if (extension !in setOf("ttf", "otf", "ttc", "otc")) return@forEach
            // Filed under every name it answers to, because a script names the
            // family, not the file.
            for (name in familyNames(file)) found.putIfAbsent(name.lowercase(), file)
            found.putIfAbsent(file.nameWithoutExtension.lowercase(), file)
        }
        return found
    }

    /**
     * The family names inside an OpenType file.
     *
     * Only the `name` table is read, and only the family entries (1) and the
     * typographic family (16) — enough to match what a script asks for without
     * pulling in a font library.
     */
    private fun familyNames(file: File): List<String> = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val names = ArrayList<String>()
            var base = 0L
            input.seek(0)
            var tag = input.readInt()
            if (tag == 0x74746366) {          // 'ttcf': a collection
                input.skipBytes(8)
                base = input.readInt().toLong() and 0xFFFFFFFFL
                input.seek(base)
                tag = input.readInt()
            }
            if (tag != 0x00010000 && tag != 0x4F54544F) return@use names  // 'OTTO'
            val tables = input.readUnsignedShort()
            input.skipBytes(6)
            var nameOffset = -1L
            repeat(tables) {
                val record = ByteArray(4)
                input.readFully(record)
                input.skipBytes(4)
                val offset = input.readInt().toLong() and 0xFFFFFFFFL
                input.skipBytes(4)
                if (String(record, Charsets.US_ASCII) == "name") nameOffset = offset
            }
            if (nameOffset < 0) return@use names

            input.seek(nameOffset)
            input.skipBytes(2)
            val count = input.readUnsignedShort()
            val storage = input.readUnsignedShort()
            data class Record(val platform: Int, val encoding: Int, val length: Int, val offset: Int)

            val records = ArrayList<Record>(count)
            repeat(count) {
                val platform = input.readUnsignedShort()
                val encoding = input.readUnsignedShort()
                input.skipBytes(2)
                val nameId = input.readUnsignedShort()
                val length = input.readUnsignedShort()
                val offset = input.readUnsignedShort()
                if (nameId == 1 || nameId == 16) {
                    records.add(Record(platform, encoding, length, offset))
                }
            }
            for (record in records) {
                input.seek(nameOffset + storage + record.offset)
                val bytes = ByteArray(record.length)
                input.readFully(bytes)
                val charset = when {
                    record.platform == 3 -> Charsets.UTF_16BE
                    record.platform == 0 -> Charsets.UTF_16BE
                    else -> Charsets.US_ASCII
                }
                val name = String(bytes, charset).trim()
                if (name.isNotEmpty() && name !in names) names.add(name)
            }
            names
        }
    }.getOrDefault(emptyList())
}

/** Where a viewer drops the font pack that came with their subtitles. */
fun assFontDirectory(context: Context): File? =
    context.getExternalFilesDir("fonts") ?: File(context.filesDir, "fonts").takeIf { it.isDirectory }
