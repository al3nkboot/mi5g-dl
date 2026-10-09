package com.mvrk.vrka.mi5g.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeRecoveryTest {
    private val botCheck = "ERROR: [youtube] dQw4w9WgXcQ: Sign in to confirm you\u2019re not a bot. Use --cookies-from-browser or --cookies for the authentication."

    @Test
    fun `recognises YouTube hosts only`() {
        listOf(
            "https://www.youtube.com/watch?v=x", "https://youtu.be/x", "https://m.youtube.com/shorts/x",
            "https://music.youtube.com/watch?v=x", "https://www.youtube-nocookie.com/embed/x", "HTTPS://YOUTUBE.COM/watch?v=x",
            "https://user@www.youtube.com:443/watch?v=x",
        ).forEach { assertTrue(it, YouTubeRecovery.isYouTubeUrl(it)) }
        listOf(
            "https://notyoutube.com/x", "https://youtube.com.evil.test/x", "https://example.com/?u=https://youtube.com",
            "https://vimeo.com/1", "youtube.com", "", null,
        ).forEach { assertFalse(it.toString(), YouTubeRecovery.isYouTubeUrl(it)) }
    }

    @Test
    fun `detects the bot check in every wording`() {
        assertTrue(YouTubeRecovery.isBotCheck(botCheck))
        assertTrue(YouTubeRecovery.isBotCheck("Sign in to confirm you're not a bot"))
        assertTrue(YouTubeRecovery.isBotCheck("Please confirm you are not a bot"))
        assertFalse(YouTubeRecovery.isBotCheck("Sign in to confirm your age"))
        assertFalse(YouTubeRecovery.isBotCheck("HTTP Error 404: Not Found"))
        assertFalse(YouTubeRecovery.isBotCheck(null))
    }

    @Test
    fun `retryable versus permanent YouTube errors`() {
        listOf(
            botCheck, "ERROR: [youtube] x: The page needs to be reloaded.", "WARNING: Only images are available for download",
            "ERROR: [youtube] x: Requested format is not available", "ERROR: unable to download video data: HTTP Error 403: Forbidden",
        ).forEach { assertTrue(it, YouTubeRecovery.isRetryable(it)) }
        listOf(
            "ERROR: [youtube] x: Private video. Sign in if you've been granted access",
            "ERROR: [youtube] x: Sign in to confirm your age. This video may be inappropriate",
            "ERROR: [youtube] x: Video unavailable. This video has been removed by the uploader",
            "ERROR: [youtube] x: Join this channel to get access to members-only content",
            "ERROR: [youtube] x: This video is DRM protected",
            "HTTP Error 404: Not Found", "", null,
        ).forEach { assertFalse(it.toString(), YouTubeRecovery.isRetryable(it)) }
    }

    @Test
    fun `client plans exist in the bundled yt-dlp and avoid tv with cookies`() {
        // Client names verified against yt-dlp 2026.08.19 INNERTUBE_CLIENTS (bundled res/raw/ytdlp).
        val known = setOf("web", "web_safari", "web_embedded", "web_music", "web_creator", "mweb", "tv", "tv_simply",
            "tv_downgraded", "android", "android_vr", "ios", "visionos")
        listOf(true, false).forEach { cookies ->
            val plans = YouTubeRecovery.plans(cookies)
            assertEquals(2, plans.size)
            plans.flatMap { it.clients.split(',') }.forEach { assertTrue(it, it in known) }
            assertTrue(plans.zipWithNext().all { (a, b) -> b.delayMs > a.delayMs })
            assertTrue(plans.all { it.delayMs in 1_000..10_000 })
            assertTrue(plans.none { p -> p.clients.split(',').any { it.startsWith("tv") } })
        }
        assertTrue(YouTubeRecovery.plans(false).last().reducedQuality)
        assertTrue(YouTubeRecovery.plans(true).first().clients.contains("web"))
    }

    @Test
    fun `argument builders`() {
        val plan = YouTubeRecovery.plans(false).first()
        assertEquals(listOf("--extractor-args", "youtube:player_client=visionos,web_embedded"), YouTubeRecovery.extractorArgs(plan))
        assertEquals(emptyList<String>(), YouTubeRecovery.extractorArgs(null))
        val yt = "https://youtu.be/x"
        assertEquals(listOf("--cookies", "/data/c.txt"), YouTubeRecovery.cookiesArgs(yt, null, "/data/c.txt"))
        assertEquals(emptyList<String>(), YouTubeRecovery.cookiesArgs(yt, "https://rr1.googlevideo.com/videoplayback", "/data/c.txt"))
        assertEquals(emptyList<String>(), YouTubeRecovery.cookiesArgs("https://vimeo.com/1", null, "/data/c.txt"))
        assertEquals(emptyList<String>(), YouTubeRecovery.cookiesArgs(yt, null, null))
        assertEquals(emptyList<String>(), YouTubeRecovery.cookiesArgs(yt, null, " "))
    }

    @Test
    fun `failure message is marked and actionable`() {
        val anon = YouTubeRecovery.failureMessage(hasCookies = false, attempts = 3, nightly = false)
        assertTrue(anon.startsWith(YouTubeRecovery.BOT_CHECK_MARKER))
        assertTrue(YouTubeRecovery.isBotCheckFailureMessage(anon))
        assertTrue("Retry" in anon && "sign in" in anon && "Nightly" in anon && "3 attempts" in anon)
        val signed = YouTubeRecovery.failureMessage(hasCookies = true, attempts = 3, nightly = true)
        assertFalse("sign in under" in signed)
        assertFalse("switch yt-dlp to the Nightly" in signed)
        assertFalse(YouTubeRecovery.isBotCheckFailureMessage(botCheck))
    }

    // ---------------------------------------------------------------- retry driver

    private class Harness(val results: MutableList<Throwable?>, val cancelAfter: Int = Int.MAX_VALUE) {
        val tried = mutableListOf<String>()
        val slept = mutableListOf<Long>()
        val announced = mutableListOf<Int>()
        fun run(initial: Throwable, plans: List<YouTubeRecovery.ClientPlan>) = runBlocking {
            YouTubeRecovery.runRetries(
                initial = initial,
                plans = plans,
                textOf = { it.message.orEmpty() },
                transferStarted = { it is TransferStarted },
                isCancelled = { tried.size >= cancelAfter },
                sleep = { slept += it },
                onAttempt = { i, _, _ -> announced += i },
                attempt = { plan -> tried += plan.clients; results.removeAt(0) },
            )
        }
    }

    private class TransferStarted(msg: String) : RuntimeException(msg)

    @Test
    fun `retry stops at the first successful client`() {
        val h = Harness(mutableListOf(null))
        val out = h.run(RuntimeException(botCheck), YouTubeRecovery.plans(false))
        assertNull(out.failure)
        assertEquals(1, out.extraAttempts)
        assertEquals(listOf("visionos,web_embedded"), h.tried)
        assertEquals(listOf(2_000L), h.slept)
    }

    @Test
    fun `retry tries every plan then reports the bot check`() {
        val h = Harness(mutableListOf(RuntimeException("The page needs to be reloaded"), RuntimeException(botCheck)))
        val out = h.run(RuntimeException(botCheck), YouTubeRecovery.plans(true))
        assertEquals(2, out.extraAttempts)
        assertTrue(out.sawBotCheck)
        assertEquals(listOf("web_embedded,web_safari", "visionos,android_vr"), h.tried)
        assertEquals(listOf(0, 1), h.announced)
        assertEquals(botCheck, out.failure?.message)
    }

    @Test
    fun `non-retryable initial error is returned untouched`() {
        val initial = RuntimeException("Private video")
        val h = Harness(mutableListOf())
        val out = h.run(initial, YouTubeRecovery.plans(false))
        assertTrue(out.failure === initial)
        assertEquals(0, out.extraAttempts)
        assertTrue(h.tried.isEmpty() && h.slept.isEmpty())
    }

    @Test
    fun `retry stops on permanent error or started transfer`() {
        val permanent = Harness(mutableListOf(RuntimeException("Video unavailable"), null))
        val p = permanent.run(RuntimeException(botCheck), YouTubeRecovery.plans(false))
        assertEquals(1, p.extraAttempts)
        assertEquals("Video unavailable", p.failure?.message)

        val started = Harness(mutableListOf(TransferStarted("HTTP Error 403: Forbidden"), null))
        val s = started.run(RuntimeException(botCheck), YouTubeRecovery.plans(false))
        assertEquals(1, s.extraAttempts)
        assertTrue(s.failure is TransferStarted)
    }

    @Test
    fun `retry honours cancellation`() {
        val h = Harness(mutableListOf(RuntimeException(botCheck), null), cancelAfter = 1)
        val out = h.run(RuntimeException(botCheck), YouTubeRecovery.plans(false))
        assertEquals(1, out.extraAttempts)
        assertEquals(1, h.tried.size)
        assertTrue(out.failure != null)
    }

    @Test
    fun `no-formats symptom without bot check is retried but not labelled as bot check`() {
        val h = Harness(mutableListOf(RuntimeException("Requested format is not available"), RuntimeException("Requested format is not available")))
        val out = h.run(RuntimeException("ERROR: [youtube] x: Requested format is not available"), YouTubeRecovery.plans(false))
        assertFalse(out.sawBotCheck)
        assertEquals(2, out.extraAttempts)
    }
}
