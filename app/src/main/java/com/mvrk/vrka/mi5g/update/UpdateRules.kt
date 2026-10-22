package com.mvrk.vrka.mi5g.update

import org.json.JSONObject
import java.util.Locale

/**
 * Mi5G Dl self-updater rules (pure, unit-tested).
 *
 * Release convention on https://github.com/al3nkboot/mi5g-dl:
 *  - tag `vX.Y.Z`
 *  - APK asset `Mi5G-Dl-X.Y.Z-arm64.apk`
 *  - optional `update.json` asset: {"versionCode":2,"versionName":"1.1.0","apk":"Mi5G-Dl-1.1.0-arm64.apk","sha256":"…"}
 * Only this repository is ever queried; upstream VRKA is never contacted.
 *
 * The repository is public, so checks and downloads work WITHOUT a token: one unauthenticated API
 * call per check (GitHub allows 60/hour per IP) and assets via their public browser_download_url
 * (github.com → release-assets redirect, not counted against the API limit). An optional token is
 * only needed if the repo becomes private or to lift the rate limit; it is sent only to api.github.com.
 */
object UpdateRules {
    const val REPO = "al3nkboot/mi5g-dl"
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val API_HOST = "api.github.com"
    const val UPDATE_JSON_ASSET = "update.json"
    private val ASSET_PREFIX = "https://api.github.com/repos/$REPO/releases/assets/"
    private val BROWSER_PREFIX = "https://github.com/$REPO/releases/download/"
    const val UNAUTHENTICATED_LIMIT_PER_HOUR = 60

    private val TAG = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")
    private val LEADING_SEMVER = Regex("""^\s*v?(\d+)\.(\d+)(?:\.(\d+))?""")
    private val SHA256 = Regex("""^[0-9a-f]{64}$""")

    fun apkAssetName(version: String) = "Mi5G-Dl-$version-arm64.apk"

    data class Asset(
        val id: Long,
        val name: String,
        val size: Long,
        val apiUrl: String,
        val sha256: String?,
        /** Public download URL (null when missing or not under this repo's releases). */
        val browserUrl: String? = null,
    )

    data class Release(
        val tag: String,
        val version: String,
        val name: String,
        val notes: String,
        val publishedAt: String,
        val apk: Asset,
        val updateJson: Asset?,
        val htmlUrl: String,
    )

    data class UpdateManifest(val versionCode: Long, val versionName: String?, val apkName: String?, val sha256: String?)

    /** Parses `/releases/latest`. Returns null when the release has no matching arm64 APK asset. */
    fun parseRelease(json: String): Release? = runCatching {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.optString("tag_name").trim()
        val m = TAG.matchEntire(tag) ?: return null
        val version = "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}"
        val assets = o.optJSONArray("assets") ?: return null
        var apk: Asset? = null
        var fallbackApk: Asset? = null
        var updateJson: Asset? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val asset = Asset(
                id = a.optLong("id"),
                name = name,
                size = a.optLong("size"),
                apiUrl = a.optString("url"),
                sha256 = normalizeSha256(a.optString("digest")),
                browserUrl = a.optString("browser_download_url").takeIf { isAllowedBrowserUrl(it, tag, name) },
            )
            if (!isAllowedAssetUrl(asset.apiUrl)) continue
            when {
                name == apkAssetName(version) -> apk = asset
                name == UPDATE_JSON_ASSET -> updateJson = asset
                fallbackApk == null && name.startsWith("Mi5G-Dl-") && name.endsWith("-arm64.apk") -> fallbackApk = asset
            }
        }
        val chosen = apk ?: fallbackApk ?: return null
        if (chosen.size <= 0) return null
        Release(
            tag = tag,
            version = version,
            name = o.optString("name").ifBlank { tag },
            notes = o.optString("body"),
            publishedAt = o.optString("published_at"),
            apk = chosen,
            updateJson = updateJson,
            htmlUrl = o.optString("html_url"),
        )
    }.getOrNull()

    fun parseManifest(json: String): UpdateManifest? = runCatching {
        val o = JSONObject(json)
        val code = o.optLong("versionCode", -1)
        if (code <= 0) return null
        UpdateManifest(
            versionCode = code,
            versionName = o.optString("versionName").ifBlank { null },
            apkName = o.optString("apk").ifBlank { null },
            sha256 = normalizeSha256(o.optString("sha256")),
        )
    }.getOrNull()

    /** GitHub reports asset digests as "sha256:<hex>"; update.json may carry plain hex. */
    fun normalizeSha256(value: String?): String? {
        val v = value?.trim()?.lowercase(Locale.ROOT)?.removePrefix("sha256:") ?: return null
        return v.takeIf { SHA256.matches(it) }
    }

    /** Leading semantic version of a versionName such as "1.0.0 (based on VRKA 5.0.2)". */
    fun leadingVersion(versionName: String?): Triple<Int, Int, Int>? {
        val m = LEADING_SEMVER.find(versionName ?: return null) ?: return null
        return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifBlank { "0" }.toInt())
    }

    fun compareVersions(a: String?, b: String?): Int {
        val x = leadingVersion(a) ?: return if (leadingVersion(b) == null) 0 else -1
        val y = leadingVersion(b) ?: return 1
        return compareValuesBy(x, y, { it.first }, { it.second }, { it.third })
    }

    /**
     * Update decision: versionCode from update.json wins; without it, compare the release tag with
     * the installed versionName's leading semver.
     */
    fun isNewer(remoteVersionCode: Long?, remoteVersion: String, installedVersionCode: Long, installedVersionName: String?): Boolean =
        if (remoteVersionCode != null) remoteVersionCode > installedVersionCode
        else compareVersions(remoteVersion, installedVersionName) > 0

    /** Asset downloads must go through the API asset endpoint of this repo only. */
    fun isAllowedAssetUrl(url: String): Boolean =
        url.startsWith(ASSET_PREFIX) && url.removePrefix(ASSET_PREFIX).all(Char::isDigit) && url.length > ASSET_PREFIX.length

    /** Public asset URL must be exactly github.com/<this repo>/releases/download/<tag>/<asset name>. */
    fun isAllowedBrowserUrl(url: String?, tag: String, name: String): Boolean {
        if (url.isNullOrBlank() || tag.isBlank() || name.isBlank()) return false
        if (tag.contains('/') || name.contains('/') || tag.contains("..") || name.contains("..")) return false
        return url == "$BROWSER_PREFIX$tag/$name"
    }

    /** Is [url] one the download worker may fetch (API asset endpoint or public release download of this repo)? */
    fun isAllowedDownloadUrl(url: String): Boolean =
        isAllowedAssetUrl(url) || (url.startsWith(BROWSER_PREFIX) && url.removePrefix(BROWSER_PREFIX).let { rest ->
            val parts = rest.split('/')
            parts.size == 2 && parts.all { it.isNotBlank() && it != ".." && it != "." }
        })

    /**
     * Where to download an asset from. Without a token: the public browser_download_url (no API quota
     * used; falls back to the API asset URL, which also works unauthenticated for public repos).
     * With a token: the API asset URL (works for private repos too).
     */
    fun downloadUrl(asset: Asset, hasToken: Boolean): String =
        if (hasToken) asset.apiUrl else asset.browserUrl ?: asset.apiUrl

    /** GitHub returns 403 (or 429) with x-ratelimit-remaining: 0 / retry-after when rate limited. */
    fun isRateLimited(code: Int, remaining: String?, retryAfter: String?): Boolean =
        code == 429 || (code == 403 && (remaining?.trim() == "0" || !retryAfter.isNullOrBlank()))

    /**
     * When the next attempt may be made (epoch millis): retry-after seconds, else x-ratelimit-reset
     * (epoch seconds), else one hour from [now]. Clamped to 1 min … 2 h.
     */
    fun rateLimitRetryAt(now: Long, resetEpochSeconds: String?, retryAfterSeconds: String?): Long {
        val candidate = retryAfterSeconds?.trim()?.toLongOrNull()?.let { now + it * 1000 }
            ?: resetEpochSeconds?.trim()?.toLongOrNull()?.let { it * 1000 }
            ?: (now + 3_600_000L)
        return candidate.coerceIn(now + 60_000L, now + 2 * 3_600_000L)
    }

    fun rateLimitMessage(retryAt: Long, now: Long, hasToken: Boolean): String {
        val minutes = ((retryAt - now + 59_999) / 60_000).coerceAtLeast(1)
        val base = "GitHub rate limit reached. Try again in about $minutes min."
        return if (hasToken) base
        else "$base Without a token GitHub allows $UNAUTHENTICATED_LIMIT_PER_HOUR requests per hour per network; the daily check stays well within it."
    }

    /** Hosts the updater may talk to (API + GitHub's signed asset storage redirects). */
    fun isAllowedHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT) ?: return false
        return h == API_HOST || h == "github.com" || h.endsWith(".githubusercontent.com")
    }

    /** The token is sent ONLY to api.github.com, never to redirect targets (signed storage URLs). */
    fun shouldSendToken(host: String?): Boolean = host?.lowercase(Locale.ROOT) == API_HOST

    fun sameSigners(installed: Set<String>, candidate: Set<String>): Boolean =
        installed.isNotEmpty() && candidate.isNotEmpty() && installed == candidate

    data class ApkFacts(
        val packageName: String?,
        val versionCode: Long,
        val versionName: String?,
        val signerSha256: Set<String>,
        val sizeBytes: Long,
        val sha256: String?,
    )

    /** Returns an error message, or null when the downloaded APK may be offered for installation. */
    fun verifyApk(
        candidate: ApkFacts,
        installed: ApkFacts,
        expectedSize: Long,
        expectedSha256: String?,
        expectedVersionCode: Long?,
    ): String? {
        if (expectedSize > 0 && candidate.sizeBytes != expectedSize) return "Downloaded size does not match the release asset."
        val sha = normalizeSha256(expectedSha256)
        if (sha != null && candidate.sha256?.lowercase(Locale.ROOT) != sha) return "SHA-256 checksum does not match the release."
        if (candidate.packageName == null) return "The file is not a valid APK."
        if (candidate.packageName != installed.packageName) return "APK package (${candidate.packageName}) is not this app."
        if (expectedVersionCode != null && candidate.versionCode != expectedVersionCode) return "APK versionCode does not match update.json."
        if (candidate.versionCode <= installed.versionCode) return "APK is not newer than the installed version."
        if (!sameSigners(installed.signerSha256, candidate.signerSha256)) {
            return "شهادة توقيع التحديث لا تطابق التطبيق المثبّت، تم رفض التثبيت. / Signing certificate does not match the installed app. Update refused."
        }
        return null
    }

    /** Human readable errors for GitHub API failures. */
    fun explainHttp(code: Int, hasToken: Boolean): String = when (code) {
        401 -> "GitHub rejected the saved token (401). It may be expired or mistyped — replace or remove it under Advanced."
        403 -> if (hasToken) "GitHub refused access (403): the token lacks Contents read access to $REPO."
        else "GitHub refused access (403). Try again later."
        404 -> if (hasToken) "Release not found (404). Check that the token has access to $REPO and that a release exists."
        else "No release found (404). If $REPO has been made private, add a token under Advanced."
        429 -> "GitHub rate limit reached. Try again later."
        else -> "GitHub returned HTTP $code."
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
