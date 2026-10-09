package com.mvrk.vrka.mi5g.youtube

import org.json.JSONArray
import java.util.Locale

/**
 * Mi5G Dl: converts cookies read from the GeckoView sign-in session into a Netscape cookies.txt
 * for yt-dlp `--cookies`. Only youtube.com / google.com cookies are ever written. Pure + unit-tested.
 */
object NetscapeCookies {
    data class Cookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String = "/",
        val secure: Boolean = false,
        val httpOnly: Boolean = false,
        val hostOnly: Boolean = false,
        /** Seconds since epoch; 0 = session cookie. */
        val expires: Long = 0L,
    )

    const val HEADER = "# Netscape HTTP Cookie File"
    private val ALLOWED_BASE_DOMAINS = listOf("youtube.com", "google.com")

    /** Cookie names that only exist for a signed-in Google/YouTube session. */
    val LOGIN_COOKIES = setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID", "SID", "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO")

    fun isAllowedDomain(domain: String): Boolean {
        val d = domain.trim().trimStart('.').lowercase(Locale.ROOT)
        return ALLOWED_BASE_DOMAINS.any { d == it || d.endsWith(".$it") }
    }

    private fun isSafeField(v: String) = v.none { it == '\t' || it == '\n' || it == '\r' || it == '\u0000' }

    /** Filters, de-duplicates and drops expired / malformed cookies. */
    fun sanitize(cookies: List<Cookie>, nowSeconds: Long): List<Cookie> =
        cookies.asSequence()
            .filter { isAllowedDomain(it.domain) }
            .filter { it.name.isNotBlank() && isSafeField(it.name) && isSafeField(it.value) && isSafeField(it.domain) && isSafeField(it.path) }
            .filter { it.expires == 0L || it.expires > nowSeconds }
            .map { if (it.path.isBlank()) it.copy(path = "/") else it }
            .associateBy { Triple(it.domain.lowercase(Locale.ROOT), it.path, it.name) }
            .values.sortedWith(compareBy({ it.domain.trimStart('.') }, { it.path }, { it.name }))
            .toList()

    fun hasLogin(cookies: List<Cookie>): Boolean = cookies.any { it.name in LOGIN_COOKIES && it.value.isNotBlank() }

    /** Netscape format: domain, include-subdomains, path, secure, expiry, name, value (tab separated). */
    fun format(cookies: List<Cookie>, nowSeconds: Long): String = buildString {
        append(HEADER).append('\n')
        append("# Written by Mi5G Dl for yt-dlp. Do not share this file.\n\n")
        sanitize(cookies, nowSeconds).forEach { c ->
            val domain = if (c.hostOnly) c.domain.trimStart('.') else if (c.domain.startsWith(".")) c.domain else ".${c.domain}"
            val includeSub = if (domain.startsWith(".")) "TRUE" else "FALSE"
            if (c.httpOnly) append("#HttpOnly_")
            append(domain).append('\t').append(includeSub).append('\t').append(c.path).append('\t')
                .append(if (c.secure) "TRUE" else "FALSE").append('\t').append(c.expires).append('\t')
                .append(c.name).append('\t').append(c.value).append('\n')
        }
    }

    /** Parses the JSON array posted by the bundled cookie-export WebExtension (browser.cookies.Cookie objects). */
    fun parseExtensionJson(json: String): List<Cookie> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val session = o.optBoolean("session", false)
            val exp = o.optDouble("expirationDate", 0.0).takeIf { !it.isNaN() } ?: 0.0
            Cookie(
                name = o.optString("name"),
                value = o.optString("value"),
                domain = o.optString("domain"),
                path = o.optString("path", "/"),
                secure = o.optBoolean("secure"),
                httpOnly = o.optBoolean("httpOnly"),
                hostOnly = o.optBoolean("hostOnly"),
                expires = if (session) 0L else exp.toLong(),
            )
        }
    }.getOrDefault(emptyList())

    /** Counts cookies in an existing cookies.txt (for the Settings status line; never returns values). */
    fun countEntries(text: String): Int = text.lineSequence().count { line ->
        val l = line.removePrefix("#HttpOnly_")
        l.isNotBlank() && !l.startsWith("#") && l.split('\t').size == 7
    }
}
