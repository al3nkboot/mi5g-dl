package com.mvrk.vrka.mi5g.share

/**
 * Mi5G Dl: extracts the media link from text shared by other apps (X, YouTube, TikTok, Instagram,
 * browsers...). Shared text often wraps the link in other words ("Check this out https://... via @x").
 */
object ShareUrlExtractor {
    private val HTTP_URL = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    /** Bare links some apps share without a scheme, e.g. "youtu.be/abc" or "www.tiktok.com/@a/video/1". */
    private val BARE_URL = Regex(
        """(?<![\w@./-])((?:www\.|m\.|vm\.|vt\.|mobile\.)?(?:[a-z0-9-]+\.)*(?:youtube\.com|youtu\.be|x\.com|twitter\.com|tiktok\.com|instagram\.com|facebook\.com|fb\.watch|reddit\.com|redd\.it|vimeo\.com|dailymotion\.com|twitch\.tv|soundcloud\.com|threads\.net|pinterest\.com|pin\.it|snapchat\.com|bilibili\.com|t\.me)/[^\s<>"'`]*)""",
        RegexOption.IGNORE_CASE,
    )

    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ']', '}', '"', '\'', '>', '…', '»', '،', '؟', '*')

    /** Known media hosts get priority when the shared text contains several links. */
    val MEDIA_HOSTS = listOf(
        "youtube.com", "youtu.be", "x.com", "twitter.com", "tiktok.com", "instagram.com",
        "facebook.com", "fb.watch", "reddit.com", "redd.it", "v.redd.it", "vimeo.com", "dailymotion.com",
        "twitch.tv", "soundcloud.com", "threads.net", "pinterest.com", "pin.it", "bilibili.com",
    )

    fun extractUrl(text: String?): String? = extractAll(text).let { all ->
        all.firstOrNull { isMediaHost(hostOf(it)) } ?: all.firstOrNull()
    }

    fun extractAll(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val found = mutableListOf<Pair<Int, String>>()
        HTTP_URL.findAll(text).forEach { m -> clean(m.value)?.let { found += m.range.first to it } }
        BARE_URL.findAll(text).forEach { m ->
            val overlapping = found.any { (start, url) -> m.range.first in start until start + url.length + 8 }
            if (!overlapping) clean("https://" + m.groupValues[1])?.let { found += m.range.first to it }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun clean(raw: String): String? {
        var url = raw
        // Strip trailing punctuation; keep balanced parentheses (Wikipedia-style links) but drop an
        // unmatched closing one, e.g. "(see https://youtu.be/x)".
        while (true) {
            val before = url
            url = url.trimEnd(*TRAILING)
            if (url.endsWith(")") && url.count { it == '(' } < url.count { it == ')' }) url = url.dropLast(1)
            if (url == before) break
        }
        val host = hostOf(url) ?: return null
        if (!host.contains('.')) return null
        return url
    }

    fun hostOf(url: String): String? = runCatching {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase().ifBlank { null }
    }.getOrNull()

    fun isMediaHost(host: String?): Boolean {
        if (host == null) return false
        return MEDIA_HOSTS.any { host == it || host.endsWith(".$it") }
    }
}
