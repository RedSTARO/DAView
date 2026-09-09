package com.daview.server.library

/**
 * Filename/folder conventions used by Emby, Jellyfin and most Chinese
 * "刮削" tools. The reference share this was written against looks like:
 *
 * ```
 * /Ani/Bocchi the Rock! (2022)/Season 01/Bocchi the Rock! - S01E01.mkv
 * /Ani/Bocchi the Rock! (2022)/Season 01/Bocchi the Rock! - S01E01.zh-Hans.default.ass
 * /Movie/Spirited Away (2001)/Spirited Away (2001).mkv
 * /Movie/Spirited Away (2001)/Extras/...
 * /TV/iPartment (2009)/iPartment The Movie (2018)/...
 * ```
 */
object NameParser {

    val videoExtensions = setOf(
        "mkv", "mp4", "avi", "mov", "wmv", "flv", "ts", "m2ts", "mts",
        "webm", "rmvb", "rm", "m4v", "mpg", "mpeg", "3gp", "ogv", "vob"
    )

    val subtitleExtensions = setOf("srt", "ass", "ssa", "sub", "vtt", "sup", "idx", "smi")

    /** Artwork a scraper-less library can still show, by the names everyone uses. */
    private val posterNames = setOf("poster", "folder", "cover", "default", "movie", "show")
    private val backdropNames = setOf("fanart", "backdrop", "background", "banner")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    private fun imageRole(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return null
        if (name.substring(dot + 1).lowercase() !in imageExtensions) return null
        return when (name.substring(0, dot).lowercase().substringBefore('-').trim()) {
            in posterNames -> "poster"
            in backdropNames -> "backdrop"
            else -> null
        }
    }

    /** True for `poster.jpg`, `folder.png` and the rest of that family. */
    fun isPosterImage(name: String) = imageRole(name) == "poster"

    /** True for `fanart.jpg` and friends. */
    fun isBackdropImage(name: String) = imageRole(name) == "backdrop"

    /**
     * Directories that belong to the filesystem rather than to the catalogue.
     *
     * A share pointed at a NAS carries these everywhere — Synology puts an
     * `@eaDir` beside every folder, the share root has a `#recycle`, Syncthing
     * leaves `.stfolder` — and each of them was being read as a title, scraped
     * against a metadata source, and given whatever poster came back first.
     */
    private val systemFolders = setOf(
        "@eadir", "#recycle", "#snapshot", ".stfolder", ".stversions",
        "lost+found", "\$recycle.bin", "system volume information", ".ds_store",
        ".thumbnails", "@tmp"
    )

    /**
     * Folders release groups put subtitles in, beside the video rather than
     * next to it. Common enough in Chinese releases that not looking inside
     * them reads as "this film has no subtitles".
     */
    private val subtitleFolders = setOf(
        "subs", "sub", "subtitles", "subtitle", "字幕", "中文字幕", "chs", "cht",
        "chs&jpn", "chs&eng", "gb", "big5", "sc", "tc"
    )

    fun isSubtitleFolder(name: String) = name.lowercase() in subtitleFolders

    fun isSystemFolder(name: String): Boolean {
        val lower = name.lowercase()
        return lower in systemFolders || lower.startsWith("@") || lower.startsWith("#")
    }

    /**
     * Directories that hold something other than the run.
     *
     * The names come from what release groups actually ship, which is why the
     * singular and the plural of the same word both appear: a folder called
     * `SPs` or `menu` was not matched by `sp` or `menus`, so the scanner read
     * it as a nested film and produced a movie titled "SPs".
     *
     * `specials` is deliberately absent — `parseSeasonFolder` already reads it
     * as season zero, which is what it is.
     */
    private val extrasFolders = setOf(
        "extras", "featurettes", "trailers", "behind the scenes", "deleted scenes",
        "interviews", "scenes", "shorts", "samples", "other", "specials extras",
        "sp", "sps", "cd", "cds", "nc", "ncop", "nced", "ncop&nced", "nc op&ed",
        "menu", "menus", "pv", "pvs", "cm", "cms", "scans", "scan", "fonts", "font",
        "previews", "preview", "bonus", "bd", "bdmenu",
        "花絮", "特典", "特典映像", "映像特典", "预告", "彩蛋", "菜单", "扫图"
    )

    private val subtitleFlags = setOf("default", "forced", "sdh", "hi", "cc", "full", "normal")

    private val languageAliases = mapOf(
        "chs" to "zh-Hans", "sc" to "zh-Hans", "gb" to "zh-Hans", "zh-hans" to "zh-Hans",
        "zh-cn" to "zh-Hans", "简体" to "zh-Hans", "简" to "zh-Hans",
        "cht" to "zh-Hant", "tc" to "zh-Hant", "big5" to "zh-Hant", "zh-hant" to "zh-Hant",
        "zh-tw" to "zh-Hant", "zh-hk" to "zh-Hant", "繁体" to "zh-Hant", "繁" to "zh-Hant",
        "zh" to "zh", "chi" to "zh", "zho" to "zh",
        "jp" to "ja", "jpn" to "ja", "ja" to "ja",
        "en" to "en", "eng" to "en",
        "kr" to "ko", "kor" to "ko", "ko" to "ko",
        "fre" to "fr", "fra" to "fr", "fr" to "fr",
        "ger" to "de", "deu" to "de", "de" to "de",
        "rus" to "ru", "ru" to "ru",
        "spa" to "es", "es" to "es",
        "ita" to "it", "it" to "it",
        "por" to "pt", "pt" to "pt",
        "tha" to "th", "th" to "th",
        "vie" to "vi", "vi" to "vi"
    )

    // ---------------------------------------------------------------- titles

    data class TitleInfo(
        val title: String,
        val year: Int?,
        val providerIds: Map<String, String>
    )

    private val yearRegex = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")
    private val trailingYearRegex = Regex("""[\s._-]((?:19|20)\d{2})$""")
    private val providerIdRegex = Regex("""[\[{](tmdb|tvdb|imdb|bgm|bangumi)(?:id)?[-=](\w+)[\]}]""", RegexOption.IGNORE_CASE)

    /** Splits `Some Title (2022) [tmdbid-1234]` into its parts. */
    fun parseTitle(raw: String): TitleInfo {
        var working = raw.trim()
        val providerIds = mutableMapOf<String, String>()
        providerIdRegex.findAll(working).forEach { match ->
            val key = when (match.groupValues[1].lowercase()) {
                "bgm", "bangumi" -> "bangumi"
                else -> match.groupValues[1].lowercase()
            }
            providerIds[key] = match.groupValues[2]
        }
        working = providerIdRegex.replace(working, " ")

        var year: Int? = null
        yearRegex.find(working)?.let {
            year = it.groupValues[1].toIntOrNull()
            working = working.replace(it.value, " ")
        }
        if (year == null) {
            trailingYearRegex.find(working)?.let {
                year = it.groupValues[1].toIntOrNull()
                working = working.removeRange(it.range)
            }
        }

        val title = working
            .replace(Regex("""[\[({][^\])}]*[\])}]"""), " ")
            .replace('_', ' ')
            .replace(Regex("""\s{2,}"""), " ")
            .trim(' ', '.', '-', '·')
        return TitleInfo(title.ifBlank { raw.trim() }, year, providerIds)
    }

    // ---------------------------------------------------------------- seasons

    private val seasonFolderPatterns = listOf(
        Regex("""^season\s*(\d{1,3})$""", RegexOption.IGNORE_CASE),
        Regex("""^s(\d{1,3})$""", RegexOption.IGNORE_CASE),
        Regex("""^第\s*([0-9一二三四五六七八九十]{1,3})\s*[季部]$"""),
        Regex("""^(\d{1,2})$""")
    )

    private val chineseNumbers = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10
    )

    /** Returns the season number for a folder name, or null when it is not a season folder. */
    fun parseSeasonFolder(name: String): Int? {
        val trimmed = name.trim()
        if (trimmed.equals("specials", true) || trimmed == "特典" || trimmed.equals("sp", true)) return 0
        for (pattern in seasonFolderPatterns) {
            val match = pattern.find(trimmed) ?: continue
            val raw = match.groupValues[1]
            raw.toIntOrNull()?.let { return it }
            return parseChineseNumber(raw)
        }
        return null
    }

    private fun parseChineseNumber(text: String): Int? {
        text.toIntOrNull()?.let { return it }
        if (text.length == 1) return chineseNumbers[text[0]]
        if (text.length == 2 && text[0] == '十') return 10 + (chineseNumbers[text[1]] ?: return null)
        if (text.length == 2 && text[1] == '十') return (chineseNumbers[text[0]] ?: return null) * 10
        if (text.length == 3 && text[1] == '十') {
            val tens = chineseNumbers[text[0]] ?: return null
            val ones = chineseNumbers[text[2]] ?: return null
            return tens * 10 + ones
        }
        return null
    }

    fun isExtrasFolder(name: String): Boolean = name.trim().lowercase() in extrasFolders

    // ---------------------------------------------------------------- episodes

    data class EpisodeInfo(
        val season: Int?,
        val episode: Int,
        val endEpisode: Int? = null,
        val title: String?
    )

    private val episodePatterns = listOf(
        // Name - S01E01 / S01E01-E02 / s01.e01
        Regex("""[sS](\d{1,3})[\s._-]*[eE](\d{1,4})(?:[\s._-]*[eE-](\d{1,4}))?"""),
        // 1x01
        Regex("""(?<![0-9a-zA-Z])(\d{1,2})[xX](\d{1,4})(?![0-9])"""),
        // 第01集 / 第01话 / 第01話
        Regex("""第\s*(\d{1,4})\s*[集话話期]"""),
        // Name - 01 [tags]  (very common for fansub anime)
        Regex("""[\s._-]-[\s._]*(\d{1,4})(?:v\d)?(?=[\s._\[\(]|$)"""),
        // EP01 / E01 / Episode 01
        Regex("""(?:^|[\s._\[-])(?:ep?|episode)[\s._]*(\d{1,4})(?![0-9])""", RegexOption.IGNORE_CASE)
    )

    /**
     * Parses an episode number out of a file name. [folderSeason] is used when
     * the file name itself carries no season.
     */
    fun parseEpisode(fileName: String, folderSeason: Int?): EpisodeInfo? {
        val base = fileName.substringBeforeLast('.')

        episodePatterns[0].find(base)?.let { match ->
            return EpisodeInfo(
                season = match.groupValues[1].toIntOrNull(),
                episode = match.groupValues[2].toIntOrNull() ?: return null,
                endEpisode = match.groupValues.getOrNull(3)?.toIntOrNull(),
                title = titleAfter(base, match.range.last)
            )
        }
        episodePatterns[1].find(base)?.let { match ->
            return EpisodeInfo(
                season = match.groupValues[1].toIntOrNull(),
                episode = match.groupValues[2].toIntOrNull() ?: return null,
                title = titleAfter(base, match.range.last)
            )
        }
        for (index in 2 until episodePatterns.size) {
            val match = episodePatterns[index].find(base) ?: continue
            val number = match.groupValues[1].toIntOrNull() ?: continue
            // Guard against matching a resolution or a year.
            if (number in 1900..2100 && index == 3) continue
            return EpisodeInfo(
                season = folderSeason,
                episode = number,
                title = titleAfter(base, match.range.last)
            )
        }
        return null
    }

    private fun titleAfter(base: String, index: Int): String? {
        if (index + 1 >= base.length) return null
        val tail = base.substring(index + 1)
            .replace(Regex("""[\[({][^\])}]*[\])}]"""), " ")
            .trim(' ', '.', '-', '_')
        return tail.takeIf { it.length > 1 && !it.all { c -> c.isDigit() } }
    }

    // ---------------------------------------------------------------- subtitles

    data class SubtitleInfo(
        val videoBaseName: String,
        val languages: List<String>,
        val isDefault: Boolean,
        val isForced: Boolean,
        val extension: String
    ) {
        val language: String? get() = languages.firstOrNull()
        val title: String?
            get() = if (languages.size > 1) languages.joinToString("/") else null
    }

    /**
     * Splits `Bocchi - S01E01.zh-Hans.default.ass` into the video base name plus
     * the language and flag tokens that follow it.
     */
    fun parseSubtitle(fileName: String): SubtitleInfo? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension !in subtitleExtensions) return null
        val withoutExt = fileName.substringBeforeLast('.')

        val languages = mutableListOf<String>()
        var isDefault = false
        var isForced = false
        var base = withoutExt

        while (true) {
            val dot = base.lastIndexOf('.')
            if (dot <= 0) break
            val token = base.substring(dot + 1)
            val lower = token.lowercase()
            when {
                lower in subtitleFlags -> {
                    if (lower == "default") isDefault = true
                    if (lower == "forced") isForced = true
                }
                normaliseLanguage(lower) != null -> languages += normaliseLanguage(lower)!!
                else -> return SubtitleInfo(base, languages.reversed(), isDefault, isForced, extension)
            }
            base = base.substring(0, dot)
        }
        return SubtitleInfo(base, languages.reversed(), isDefault, isForced, extension)
    }

    fun normaliseLanguage(token: String): String? {
        val lower = token.lowercase()
        languageAliases[lower]?.let { return it }
        // Bare BCP-47 such as `pt-BR` that is not in the alias table.
        if (Regex("""^[a-z]{2,3}(-[a-z]{2,4})?$""").matches(lower) && lower.length <= 7) {
            return if (lower in setOf("mkv", "mp4", "srt", "ass", "www", "com", "web")) null else lower
        }
        return null
    }

    // ---------------------------------------------------------------- misc

    fun isVideoFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in videoExtensions

    fun isSubtitleFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in subtitleExtensions

    /** Sample/trailer files that should never become library entries. */
    fun isJunkFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("._") ||
            lower == "thumbs.db" ||
            lower == ".ds_store" ||
            lower.endsWith(".url") ||
            lower.endsWith(".lnk") ||
            lower.endsWith(".txt") ||
            lower.endsWith(".nfo.bak") ||
            Regex("""(^|[\s._-])(sample|trailer|preview)([\s._-]|$)""").containsMatchIn(lower)
    }

    /** Sort key that keeps `The Matrix` next to `Matrix` and orders numbers naturally. */
    fun sortName(name: String): String {
        var value = name.trim().lowercase()
        for (article in listOf("the ", "a ", "an ")) {
            if (value.startsWith(article)) {
                value = value.removePrefix(article)
                break
            }
        }
        return Regex("""\d+""").replace(value) { it.value.padStart(6, '0') }
    }
}
