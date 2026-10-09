package com.mvrk.vrka.mi5g.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class YouTubeCookieStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_800_000_000L

    @Test
    fun `writes, reports and clears the cookie file`() {
        val dir = tmp.newFolder("mi5g")
        val store = YouTubeCookieStore(dir)
        assertFalse(store.exists())
        assertNull(store.pathIfPresent())
        val n = store.write(listOf(
            NetscapeCookies.Cookie("SAPISID", "secret", ".youtube.com", secure = true, expires = now + 60),
            NetscapeCookies.Cookie("X", "y", ".example.com", expires = now + 60),
        ), now)
        assertEquals(1, n)
        assertTrue(store.exists())
        assertEquals(store.file.absolutePath, store.pathIfPresent())
        assertEquals(1, store.entryCount())
        val text = store.file.readText()
        assertTrue(text.startsWith(NetscapeCookies.HEADER))
        assertFalse("example.com" in text)
        assertFalse(java.io.File(dir, "youtube_cookies.txt.tmp").exists())
        assertTrue(store.file.canRead() && store.file.canWrite())
        assertTrue(store.clear())
        assertFalse(store.file.exists())
        assertEquals(0, store.entryCount())
        assertTrue(store.clear())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses to write an empty cookie file`() {
        YouTubeCookieStore(tmp.newFolder()).write(listOf(NetscapeCookies.Cookie("A", "b", ".facebook.com")), now)
    }

    @Test
    fun `rewrite replaces previous cookies`() {
        val store = YouTubeCookieStore(tmp.newFolder())
        store.write(listOf(NetscapeCookies.Cookie("A", "1", ".youtube.com"), NetscapeCookies.Cookie("B", "2", ".youtube.com")), now)
        store.write(listOf(NetscapeCookies.Cookie("C", "3", ".youtube.com")), now)
        assertEquals(1, store.entryCount())
        assertFalse("\tA\t" in store.file.readText())
    }
}
