package com.mvrk.vrka.mi5g.youtube

import java.util.Locale

/**
 * Mi5G Dl: YouTube direct-extraction resilience (pure, unit-tested).
 *
 * YouTube intermittently answers yt-dlp's player requests with "Sign in to confirm you're not a
 * bot" (IP/session reputation). Different InnerTube clients are challenged differently, so before
 * giving up we retry a small number of times with other `player_client` values.
 *
 * Client choice was checked against the bundled yt-dlp 2026.08.19 (and nightly 2026.09.27):
 *  - defaults are `visionos,web` (signed out) and `web_embedded,tv_downgraded,web` (signed in);
 *  - `visionos` needs neither a JS player nor a PO token and returns the full DASH ladder;
 *  - `web_embedded`/`web_safari` honour cookies and need the JS challenge solver (QuickJS + EJS);
 *  - `android_vr`/`android` only return the progressive 360p format without a PO token, so they
 *    are a last resort and the job is labelled as reduced quality;
 *  - `tv`/`tv_downgraded` currently fail signed-out with "The page needs to be reloaded", and the
 *    yt-dlp docs warn that pairing cookies with `tv` can log the browser session out — not used.
 * A browser fallback cannot help on YouTube: the web player streams via SABR/UMP POST requests,
 * which are not downloadable media URLs.
 */
object YouTubeRecovery {
    /** Marker that starts the final failure message so Queue/History can show a tailored explanation. */
    const val BOT_CHECK_MARKER = "YouTube bot check:"

    data class ClientPlan(
        val clients: String,
        val label: String,
        val delayMs: Long,
        val reducedQuality: Boolean = false,
    )

    private val YT_HOSTS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")

    fun isYouTubeUrl(url: String?): Boolean {
        val host = hostOf(url ?: return false) ?: return false
        return YT_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun hostOf(url: String): String? {
        val after = url.trim().substringAfter("://", "").ifEmpty { return null }
        return after.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT).ifBlank { null }
    }

    /** "Sign in to confirm you're not a bot" (straight or curly apostrophe), in any yt-dlp wording. */
    fun isBotCheck(text: String?): Boolean {
        val t = text?.lowercase(Locale.ROOT)?.replace('’', '\'') ?: return false
        return ("confirm you're not a bot" in t) || ("confirm you are not a bot" in t) ||
            ("sign in to confirm" in t && "bot" in t)
    }

    /**
     * Failures worth another attempt with a different player client: the bot check and the
     * client-specific symptoms of it (no formats / only storyboards, "page needs to be reloaded",
     * HTTP 403 on the media request). Never retried: private, age-restricted, removed, DRM.
     */
    fun isRetryable(text: String?): Boolean {
        val t = text?.lowercase(Locale.ROOT)?.replace('’', '\'') ?: return false
        if (NON_RETRYABLE.any { it in t }) return false
        return isBotCheck(t) || RETRYABLE.any { it in t }
    }

    private val RETRYABLE = listOf(
        "the page needs to be reloaded",
        "only images are available",
        "requested format is not available",
        "no video formats found",
        "http error 403",
        "403: forbidden",
    )
    private val NON_RETRYABLE = listOf(
        "private video", "this video is private", "video unavailable", "this video is unavailable",
        "has been removed", "confirm your age", "age-restricted", "members-only", "join this channel",
        "drm protected", "drm-protected", "copyright", "not available in your country",
    )

    /** Alternative clients tried after the default attempt fails (at most two extra requests). */
    fun plans(hasCookies: Boolean): List<ClientPlan> = if (hasCookies) {
        listOf(
            ClientPlan("web_embedded,web_safari", "web clients with your YouTube sign-in", delayMs = 2_000),
            ClientPlan("visionos,android_vr", "visionOS / Android VR clients", delayMs = 5_000),
        )
    } else {
        listOf(
            ClientPlan("visionos,web_embedded", "visionOS / embedded clients", delayMs = 2_000),
            ClientPlan("android_vr,android", "Android clients (may be limited to 360p)", delayMs = 5_000, reducedQuality = true),
        )
    }

    fun extractorArgs(plan: ClientPlan?): List<String> =
        if (plan == null) emptyList() else listOf("--extractor-args", "youtube:player_client=${plan.clients}")

    /** `--cookies <file>` only for YouTube page URLs (never for browser-captured media or other sites). */
    fun cookiesArgs(url: String?, resolvedMediaUrl: String?, cookieFile: String?): List<String> =
        if (cookieFile.isNullOrBlank() || resolvedMediaUrl != null || !isYouTubeUrl(url)) emptyList()
        else listOf("--cookies", cookieFile)

    /** Final message after all YouTube attempts failed because of the bot check. */
    fun failureMessage(hasCookies: Boolean, attempts: Int, nightly: Boolean): String = buildString {
        append(BOT_CHECK_MARKER)
        append(" YouTube asked to confirm you're not a bot and refused the download ($attempts attempts with different YouTube clients). ")
        append("This is a temporary YouTube check on your network, not a problem with the video. ")
        append("Tap Retry in a few minutes")
        if (!hasCookies) append(", or sign in under Settings → YouTube account (optional)")
        if (!nightly) append(", or switch yt-dlp to the Nightly channel and update it in Settings → Components")
        else append(", and keep yt-dlp updated in Settings → Components")
        append(". Switching between Wi-Fi and mobile data can also help.")
    }

    /** Outcome of [runRetries]: `failure == null` means one of the alternative clients succeeded. */
    data class RetryOutcome(val failure: Throwable?, val sawBotCheck: Boolean, val extraAttempts: Int)

    /**
     * The retry driver used by the download manager (pure, so it is unit-tested): tries each plan in
     * order after [ClientPlan.delayMs], stopping on success, cancellation, a started transfer or a
     * non-retryable error.
     */
    suspend fun runRetries(
        initial: Throwable,
        plans: List<ClientPlan>,
        textOf: (Throwable) -> String,
        transferStarted: (Throwable) -> Boolean,
        isCancelled: () -> Boolean,
        sleep: suspend (Long) -> Unit,
        onAttempt: (index: Int, plan: ClientPlan, sawBotCheck: Boolean) -> Unit,
        attempt: suspend (ClientPlan) -> Throwable?,
    ): RetryOutcome {
        var failure = initial
        var sawBotCheck = isBotCheck(textOf(initial))
        if (!isRetryable(textOf(initial))) return RetryOutcome(initial, sawBotCheck, 0)
        var made = 0
        for ((index, plan) in plans.withIndex()) {
            if (isCancelled()) break
            onAttempt(index, plan, sawBotCheck)
            sleep(plan.delayMs)
            if (isCancelled()) break
            made++
            val next = attempt(plan) ?: return RetryOutcome(null, sawBotCheck, made)
            failure = next
            val text = textOf(next)
            sawBotCheck = sawBotCheck || isBotCheck(text)
            if (transferStarted(next) || !isRetryable(text)) break
        }
        return RetryOutcome(failure, sawBotCheck, made)
    }

    fun isBotCheckFailureMessage(text: String?): Boolean = text?.contains(BOT_CHECK_MARKER, ignoreCase = true) == true
}
