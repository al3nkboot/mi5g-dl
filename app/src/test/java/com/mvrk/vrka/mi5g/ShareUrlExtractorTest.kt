package com.mvrk.vrka.mi5g

import com.mvrk.vrka.mi5g.share.ShareUrlExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareUrlExtractorTest {
    private fun x(t: String?) = ShareUrlExtractor.extractUrl(t)

    @Test fun plainUrl() = assertEquals("https://youtu.be/dQw4w9WgXcQ", x("https://youtu.be/dQw4w9WgXcQ"))

    @Test fun urlInsideSentence() = assertEquals(
        "https://x.com/user/status/1234567890",
        x("Check this out https://x.com/user/status/1234567890 via @someone"),
    )

    @Test fun trailingPunctuationIsStripped() {
        assertEquals("https://www.youtube.com/watch?v=abc", x("Watch: https://www.youtube.com/watch?v=abc."))
        assertEquals("https://www.youtube.com/watch?v=abc", x("(https://www.youtube.com/watch?v=abc)"))
        assertEquals("https://youtu.be/abc", x("شوف هذا https://youtu.be/abc، رهيب؟"))
        assertEquals("https://youtu.be/abc", x("\"https://youtu.be/abc\"…"))
    }

    @Test fun balancedParenthesesAreKept() =
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", x("see https://en.wikipedia.org/wiki/Foo_(bar)."))

    @Test fun mediaHostPreferredOverOtherLinks() = assertEquals(
        "https://www.tiktok.com/@a/video/123",
        x("via https://example.com/landing and https://www.tiktok.com/@a/video/123"),
    )

    @Test fun firstLinkWhenNoMediaHost() = assertEquals("https://example.com/a", x("https://example.com/a https://example.org/b"))

    @Test fun bareKnownHostGetsHttps() {
        assertEquals("https://youtu.be/abc123", x("look youtu.be/abc123 now"))
        assertEquals("https://vm.tiktok.com/ZMabc/", x("vm.tiktok.com/ZMabc/"))
    }

    @Test fun bareUnknownHostIgnored() = assertNull(x("example.com/video is nice"))

    @Test fun youtubeShareWithTitleOnFirstLine() = assertEquals(
        "https://youtube.com/shorts/AbCdEf?si=xyz",
        x("Amazing short!\nhttps://youtube.com/shorts/AbCdEf?si=xyz"),
    )

    @Test fun emptyAndNoUrl() {
        assertNull(x(null)); assertNull(x("")); assertNull(x("no links here")); assertNull(x("https://localhost/x"))
    }

    @Test fun extractAllDistinctInOrder() =
        assertEquals(listOf("https://a.com/1", "https://b.com/2"), ShareUrlExtractor.extractAll("https://a.com/1 https://b.com/2 https://a.com/1"))

    @Test fun hostHelpers() {
        assertEquals("m.youtube.com", ShareUrlExtractor.hostOf("https://m.youtube.com/watch?v=1"))
        assertTrue(ShareUrlExtractor.isMediaHost("m.youtube.com"))
        assertFalse(ShareUrlExtractor.isMediaHost("notyoutube.com"))
    }
}
