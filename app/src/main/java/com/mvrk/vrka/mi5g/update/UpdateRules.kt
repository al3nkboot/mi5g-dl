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
 */
object UpdateRules {
    const val REPO = "al3nkboot/mi5g-dl"
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val API_HOST = "api.github.com"
    const val UPDATE_JSON_ASSET = "update.json"
    private val ASSET_PREFIX = "https://api.github.com/repos/$REPO/releases/assets/"

    private val TAG = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")
    private val LEADING_SEMVER = Regex("""^\s*v?(\d+)\.(\d+)(?:\.(\d+))?""")
    private val SHA256 = Regex("""^[0-9a-f]{64}$""")

    fun apkAssetName(version: String) = "Mi5G-Dl-$version-arm64.apk"

    data class Asset(val id: Long, val name: String, val size: Long, val apiUrl: String, val sha256: String?)

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
            return "Signing certificate does not match the installed app. Update refused."
        }
        return null
    }

    /** Human readable errors for GitHub API failures. */
    fun explainHttp(code: Int, hasToken: Boolean): String = when (code) {
        401 -> "GitHub rejected the token (401). It may be expired or mistyped — create a new one."
        403 -> "GitHub refused access (403): token lacks Contents read access to $REPO, or rate limited."
        404 -> if (hasToken) "Release not found (404). Check that the token has access to $REPO and that a release exists."
        else "The update repository is private: add a GitHub token below (Settings → Mi5G Dl App Updates)."
        else -> "GitHub returned HTTP $code."
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
