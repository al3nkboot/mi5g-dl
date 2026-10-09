package com.mvrk.vrka.mi5g.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal GitHub client for the Mi5G Dl updater. Works without a token (public repo). Follows
 * redirects manually so an optional token is only ever sent to api.github.com (never to github.com
 * or the signed asset-storage redirect target).
 */
class GitHubClient(private val token: String?) {
    /** [retryAt] is set (epoch millis) when GitHub rate-limited the request. */
    class HttpException(val code: Int, message: String, val retryAt: Long? = null) : IOException(message) {
        val rateLimited: Boolean get() = retryAt != null
    }

    fun getText(url: String, accept: String = "application/vnd.github+json"): String =
        open(url, accept) { conn -> conn.inputStream.bufferedReader().use { it.readText() } }

    fun download(url: String, target: File, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean = { false }) {
        open(url, "application/octet-stream", readTimeoutMs = 60_000) { conn ->
            val total = conn.contentLengthLong
            val tmp = File(target.parentFile, target.name + ".part")
            var done = 0L
            var last = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw IOException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 250) { last = now; onProgress(done, total) }
                    }
                }
            }
            onProgress(done, total)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        }
    }

    private fun <T> open(startUrl: String, accept: String, readTimeoutMs: Int = 15_000, body: (HttpURLConnection) -> T): T {
        var current = startUrl
        repeat(MAX_REDIRECTS + 1) {
            val url = URL(current)
            if (!url.protocol.equals("https", true)) throw SecurityException("HTTPS required")
            if (!UpdateRules.isAllowedHost(url.host)) throw SecurityException("Host ${url.host} not allowed for updates")
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", "Mi5G-Dl-Updater")
            conn.setRequestProperty("Accept", accept)
            if (UpdateRules.shouldSendToken(url.host)) {
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                val code = conn.responseCode
                when {
                    code in 200..299 -> return body(conn)
                    code in setOf(301, 302, 303, 307, 308) -> {
                        val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                        current = URL(url, location).toString()
                    }
                    UpdateRules.isRateLimited(code, conn.getHeaderField("x-ratelimit-remaining"), conn.getHeaderField("retry-after")) -> {
                        val now = System.currentTimeMillis()
                        val retryAt = UpdateRules.rateLimitRetryAt(now, conn.getHeaderField("x-ratelimit-reset"), conn.getHeaderField("retry-after"))
                        throw HttpException(code, UpdateRules.rateLimitMessage(retryAt, now, !token.isNullOrBlank()), retryAt)
                    }
                    else -> throw HttpException(code, UpdateRules.explainHttp(code, !token.isNullOrBlank()))
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    companion object {
        const val MAX_REDIRECTS = 5
    }
}
