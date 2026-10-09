package com.mvrk.vrka.mi5g.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetscapeCookiesTest {
    private val now = 1_800_000_000L
    private fun c(name: String, value: String = "v", domain: String = ".youtube.com", expires: Long = now + 3600, hostOnly: Boolean = false, httpOnly: Boolean = false, secure: Boolean = true, path: String = "/") =
        NetscapeCookies.Cookie(name, value, domain, path, secure, httpOnly, hostOnly, expires)

    @Test
    fun `formats yt-dlp compatible lines`() {
        val text = NetscapeCookies.format(listOf(
            c("SAPISID", "abc"),
            c("PREF", "f6=8", domain = "www.youtube.com", hostOnly = true, secure = false, expires = 0),
            c("__Secure-3PSID", "s3", domain = "google.com", httpOnly = true),
        ), now)
        val lines = text.lines()
        assertEquals(NetscapeCookies.HEADER, lines.first())
        assertTrue(lines.contains(".google.com\tTRUE\t/\tTRUE\t${now + 3600}\t__Secure-3PSID\ts3".let { "#HttpOnly_$it" }))
        assertTrue(lines.contains(".youtube.com\tTRUE\t/\tTRUE\t${now + 3600}\tSAPISID\tabc"))
        assertTrue(lines.contains("www.youtube.com\tFALSE\t/\tFALSE\t0\tPREF\tf6=8"))
        assertEquals(3, NetscapeCookies.countEntries(text))
        lines.filter { it.isNotBlank() && (!it.startsWith("#") || it.startsWith("#HttpOnly_")) }
            .forEach { assertEquals(it, 7, it.split('\t').size) }
    }

    @Test
    fun `only youtube and google domains survive`() {
        assertTrue(NetscapeCookies.isAllowedDomain(".youtube.com"))
        assertTrue(NetscapeCookies.isAllowedDomain("accounts.google.com"))
        assertFalse(NetscapeCookies.isAllowedDomain("google.com.evil.test"))
        assertFalse(NetscapeCookies.isAllowedDomain("notgoogle.com"))
        assertFalse(NetscapeCookies.isAllowedDomain(".googlevideo.com"))
        val kept = NetscapeCookies.sanitize(listOf(c("A"), c("B", domain = ".facebook.com"), c("C", domain = "accounts.google.com")), now)
        assertEquals(listOf("C", "A"), kept.map { it.name })
    }

    @Test
    fun `drops expired, malformed and injected cookies and dedupes`() {
        val kept = NetscapeCookies.sanitize(listOf(
            c("OLD", expires = now - 1),
            c("TAB", "a\tb"),
            c("NL", "a\nevil.com\tTRUE\t/\tFALSE\t0\tx\ty"),
            c("NUL", "a\u0000"),
            c("", "empty-name"),
            c("SID", "first"),
            c("SID", "second"),
            c("SESSION", expires = 0),
        ), now)
        assertEquals(listOf("SESSION", "SID"), kept.map { it.name })
        assertEquals("second", kept.single { it.name == "SID" }.value)
    }

    @Test
    fun `parses extension json and detects login`() {
        val json = """[{"name":"SAPISID","value":"x","domain":".youtube.com","path":"/","secure":true,"httpOnly":false,"hostOnly":false,"session":false,"expirationDate":1800003600.5},
            {"name":"YSC","value":"y","domain":".youtube.com","path":"/","secure":true,"httpOnly":true,"hostOnly":false,"session":true}]"""
        val parsed = NetscapeCookies.parseExtensionJson(json)
        assertEquals(2, parsed.size)
        assertEquals(1800003600L, parsed[0].expires)
        assertEquals(0L, parsed[1].expires)
        assertTrue(parsed[1].httpOnly)
        assertTrue(NetscapeCookies.hasLogin(parsed))
        assertFalse(NetscapeCookies.hasLogin(parsed.drop(1)))
        assertEquals(emptyList<NetscapeCookies.Cookie>(), NetscapeCookies.parseExtensionJson("not json"))
    }
}
