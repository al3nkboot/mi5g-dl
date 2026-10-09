package com.mvrk.vrka.mi5g

import com.mvrk.vrka.mi5g.update.UpdateRules
import com.mvrk.vrka.mi5g.update.UpdateRules.ApkFacts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    private val sha = "a".repeat(64)
    private val assetBase = "https://api.github.com/repos/al3nkboot/mi5g-dl/releases/assets/"

    private fun asset(id: Long, name: String, size: Long = 1000, url: String = "$assetBase$id", digest: String? = "sha256:$sha", tag: String = "v1.1.1") =
        JSONObject().put("id", id).put("name", name).put("size", size).put("url", url).putOpt("digest", digest)
            .put("browser_download_url", "https://github.com/al3nkboot/mi5g-dl/releases/download/$tag/$name")

    private fun release(tag: String = "v1.1.0", vararg assets: JSONObject, draft: Boolean = false, pre: Boolean = false) =
        JSONObject().put("tag_name", tag).put("name", "Mi5G Dl $tag").put("body", "notes").put("draft", draft)
            .put("prerelease", pre).put("html_url", "https://github.com/al3nkboot/mi5g-dl/releases/tag/$tag")
            .put("assets", JSONArray(assets.toList())).toString()

    @Test fun onlyOwnRepoIsQueried() {
        assertEquals("https://api.github.com/repos/al3nkboot/mi5g-dl/releases/latest", UpdateRules.LATEST_RELEASE_URL)
        assertFalse(UpdateRules.LATEST_RELEASE_URL.contains("MaverickRox", ignoreCase = true))
        assertFalse(UpdateRules.LATEST_RELEASE_URL.contains("VRKA", ignoreCase = true))
    }

    @Test fun parsesReleaseWithApkAndManifest() {
        val r = UpdateRules.parseRelease(release("v1.1.0", asset(11, "Mi5G-Dl-1.1.0-arm64.apk", 30_000_000), asset(12, "update.json", 120)))
        assertNotNull(r); r!!
        assertEquals("1.1.0", r.version)
        assertEquals(11L, r.apk.id)
        assertEquals("${assetBase}11", r.apk.apiUrl)
        assertEquals(sha, r.apk.sha256)
        assertEquals(12L, r.updateJson?.id)
        assertEquals("notes", r.notes)
    }

    @Test fun rejectsDraftsPrereleasesBadTagsAndForeignAssets() {
        val apk = asset(11, "Mi5G-Dl-1.1.0-arm64.apk")
        assertNull(UpdateRules.parseRelease(release("v1.1.0", apk, draft = true)))
        assertNull(UpdateRules.parseRelease(release("v1.1.0", apk, pre = true)))
        assertNull(UpdateRules.parseRelease(release("latest", apk)))
        assertNull(UpdateRules.parseRelease(release("v1.1.0", asset(11, "Mi5G-Dl-1.1.0-arm64.apk", url = "https://api.github.com/repos/MaverickRox/VRKA-Android/releases/assets/11"))))
        assertNull(UpdateRules.parseRelease(release("v1.1.0", asset(11, "Mi5G-Dl-1.1.0-arm64.apk", url = "https://evil.example/assets/11"))))
        assertNull(UpdateRules.parseRelease(release("v1.1.0", asset(11, "VRKA-5.1.0.apk"))))
        assertNull(UpdateRules.parseRelease(release("v1.1.0", asset(11, "Mi5G-Dl-1.1.0-arm64.apk", size = 0))))
        assertNull(UpdateRules.parseRelease("not json"))
    }

    @Test fun fallsBackToAnyNamedApk() {
        val r = UpdateRules.parseRelease(release("v1.2.0", asset(21, "Mi5G-Dl-1.2.0-test-arm64.apk")))
        assertEquals(21L, r?.apk?.id)
    }

    @Test fun manifest() {
        val m = UpdateRules.parseManifest("""{"versionCode":2,"versionName":"1.1.0","apk":"Mi5G-Dl-1.1.0-arm64.apk","sha256":"${sha.uppercase()}"}""")
        assertEquals(2L, m?.versionCode); assertEquals(sha, m?.sha256); assertEquals("Mi5G-Dl-1.1.0-arm64.apk", m?.apkName)
        assertNull(UpdateRules.parseManifest("""{"versionName":"1.1.0"}"""))
        assertNull(UpdateRules.normalizeSha256("sha256:xyz"))
    }

    @Test fun versionComparison() {
        assertEquals(Triple(1, 0, 0), UpdateRules.leadingVersion("1.0.0 (based on VRKA 5.0.2)"))
        assertEquals(Triple(1, 1, 0), UpdateRules.leadingVersion("v1.1"))
        assertTrue(UpdateRules.compareVersions("1.1.0", "1.0.0 (based on VRKA 5.0.2)") > 0)
        assertTrue(UpdateRules.compareVersions("1.10.0", "1.9.9") > 0)
        assertEquals(0, UpdateRules.compareVersions("1.1.0", "1.1.0 (based on VRKA 5.0.2)"))
        assertTrue(UpdateRules.compareVersions("1.0.9", "1.1.0-debug") < 0)
    }

    @Test fun isNewerPrefersVersionCode() {
        assertTrue(UpdateRules.isNewer(2, "1.1.0", 1, "1.0.0 (based on VRKA 5.0.2)"))
        assertFalse(UpdateRules.isNewer(2, "1.1.0", 2, "1.1.0 (based on VRKA 5.0.2)"))
        assertFalse("versionCode wins over a higher tag", UpdateRules.isNewer(1, "9.0.0", 1, "1.0.0"))
        assertTrue(UpdateRules.isNewer(null, "1.1.0", 1, "1.0.0 (based on VRKA 5.0.2)"))
        assertFalse(UpdateRules.isNewer(null, "1.0.0", 1, "1.0.0 (based on VRKA 5.0.2)"))
    }

    @Test fun hostAndTokenPolicy() {
        assertTrue(UpdateRules.isAllowedHost("api.github.com"))
        assertTrue(UpdateRules.isAllowedHost("objects.githubusercontent.com"))
        assertTrue(UpdateRules.isAllowedHost("release-assets.githubusercontent.com"))
        assertFalse(UpdateRules.isAllowedHost("evil.com"))
        assertFalse(UpdateRules.isAllowedHost("githubusercontent.com.evil.com"))
        assertTrue(UpdateRules.shouldSendToken("api.github.com"))
        assertTrue(UpdateRules.shouldSendToken("API.GITHUB.COM"))
        assertFalse(UpdateRules.shouldSendToken("objects.githubusercontent.com"))
        assertFalse(UpdateRules.shouldSendToken("github.com"))
        assertTrue(UpdateRules.isAllowedAssetUrl("${assetBase}123"))
        assertFalse(UpdateRules.isAllowedAssetUrl(assetBase))
        assertFalse(UpdateRules.isAllowedAssetUrl("${assetBase}123/../../x"))
    }

    private val installed = ApkFacts("com.midroid.mi5gdl", 1, "1.0.0", setOf("57bc19907eea"), 0, null)
    private fun candidate(
        pkg: String? = "com.midroid.mi5gdl", code: Long = 2, signers: Set<String> = setOf("57bc19907eea"),
        size: Long = 1000, sha256: String? = sha,
    ) = ApkFacts(pkg, code, "1.1.0", signers, size, sha256)

    @Test fun verifyAcceptsMatchingApk() = assertNull(UpdateRules.verifyApk(candidate(), installed, 1000, sha, 2))

    @Test fun verifyRejections() {
        assertTrue(UpdateRules.verifyApk(candidate(signers = setOf("deadbeef")), installed, 1000, sha, 2)!!.contains("Signing certificate"))
        assertNotNull(UpdateRules.verifyApk(candidate(signers = emptySet()), installed, 1000, sha, 2))
        assertNotNull(UpdateRules.verifyApk(candidate(signers = setOf("57bc19907eea", "other")), installed, 1000, sha, 2))
        assertNotNull(UpdateRules.verifyApk(candidate(pkg = "com.mvrk.vrka"), installed, 1000, sha, 2))
        assertNotNull(UpdateRules.verifyApk(candidate(pkg = null), installed, 1000, sha, 2))
        assertNotNull(UpdateRules.verifyApk(candidate(code = 1), installed, 1000, sha, null))
        assertNotNull(UpdateRules.verifyApk(candidate(code = 3), installed, 1000, sha, 2))
        assertNotNull(UpdateRules.verifyApk(candidate(size = 999), installed, 1000, sha, 2))
        assertNotNull(UpdateRules.verifyApk(candidate(sha256 = "b".repeat(64)), installed, 1000, sha, 2))
    }

    // ------------------------------------------------------------ 1.1.1: unauthenticated path
    @Test fun parsesPublicBrowserDownloadUrl() {
        val r = UpdateRules.parseRelease(release("v1.1.1", asset(31, "Mi5G-Dl-1.1.1-arm64.apk", 155_000_000), asset(32, "update.json", 150)))!!
        assertEquals("https://github.com/al3nkboot/mi5g-dl/releases/download/v1.1.1/Mi5G-Dl-1.1.1-arm64.apk", r.apk.browserUrl)
        assertEquals("https://github.com/al3nkboot/mi5g-dl/releases/download/v1.1.1/update.json", r.updateJson?.browserUrl)
    }

    @Test fun foreignBrowserUrlIsDropped() {
        val evil = asset(31, "Mi5G-Dl-1.1.1-arm64.apk").put("browser_download_url", "https://github.com/MaverickRox/VRKA-Android/releases/download/v1.1.1/Mi5G-Dl-1.1.1-arm64.apk")
        val r = UpdateRules.parseRelease(release("v1.1.1", evil))!!
        assertNull(r.apk.browserUrl)
        // without a public URL the unauthenticated download falls back to the API asset URL of this repo
        assertEquals("${assetBase}31", UpdateRules.downloadUrl(r.apk, hasToken = false))
        assertFalse(UpdateRules.isAllowedBrowserUrl("https://github.com/al3nkboot/mi5g-dl/releases/download/v1.1.1/other.apk", "v1.1.1", "Mi5G-Dl-1.1.1-arm64.apk"))
        assertFalse(UpdateRules.isAllowedBrowserUrl("https://github.com/al3nkboot/mi5g-dl/releases/download/../x/a.apk", "..", "a.apk"))
    }

    @Test fun downloadUrlChoiceDependsOnToken() {
        val r = UpdateRules.parseRelease(release("v1.1.1", asset(31, "Mi5G-Dl-1.1.1-arm64.apk")))!!
        assertEquals(r.apk.browserUrl, UpdateRules.downloadUrl(r.apk, hasToken = false))
        assertEquals("${assetBase}31", UpdateRules.downloadUrl(r.apk, hasToken = true))
    }

    @Test fun allowedDownloadUrls() {
        assertTrue(UpdateRules.isAllowedDownloadUrl("${assetBase}31"))
        assertTrue(UpdateRules.isAllowedDownloadUrl("https://github.com/al3nkboot/mi5g-dl/releases/download/v1.1.1/Mi5G-Dl-1.1.1-arm64.apk"))
        assertFalse(UpdateRules.isAllowedDownloadUrl("https://github.com/MaverickRox/VRKA-Android/releases/download/v5.0.2/VRKA.apk"))
        assertFalse(UpdateRules.isAllowedDownloadUrl("https://github.com/al3nkboot/mi5g-dl/releases/download/v1/../../x/y"))
        assertFalse(UpdateRules.isAllowedDownloadUrl("http://github.com/al3nkboot/mi5g-dl/releases/download/v1.1.1/a.apk"))
        assertFalse(UpdateRules.isAllowedDownloadUrl("https://evil.com/al3nkboot/mi5g-dl/releases/download/v1.1.1/a.apk"))
        // the public github.com host never receives the token; only api.github.com does
        assertTrue(UpdateRules.isAllowedHost("github.com"))
        assertFalse(UpdateRules.shouldSendToken("github.com"))
        assertFalse(UpdateRules.shouldSendToken("release-assets.githubusercontent.com"))
    }

    @Test fun notFoundWithoutTokenNoLongerClaimsPrivate() {
        val m = UpdateRules.explainHttp(404, hasToken = false)
        assertTrue(m.contains("If al3nkboot/mi5g-dl has been made private"))
        assertFalse(UpdateRules.explainHttp(403, hasToken = false).contains("token lacks"))
    }

    // ------------------------------------------------------------ 1.1.1: rate limits
    @Test fun rateLimitDetection() {
        assertTrue(UpdateRules.isRateLimited(403, "0", null))
        assertTrue(UpdateRules.isRateLimited(403, null, "60"))
        assertTrue(UpdateRules.isRateLimited(429, null, null))
        assertFalse("403 for missing permission is not a rate limit", UpdateRules.isRateLimited(403, "42", null))
        assertFalse(UpdateRules.isRateLimited(404, "0", null))
        assertFalse(UpdateRules.isRateLimited(200, "0", null))
    }

    @Test fun rateLimitRetryTime() {
        val now = 1_760_000_000_000L
        assertEquals(now + 120_000, UpdateRules.rateLimitRetryAt(now, null, "120"))
        assertEquals((now / 1000 + 1800) * 1000, UpdateRules.rateLimitRetryAt(now, (now / 1000 + 1800).toString(), null))
        assertEquals("retry-after wins over reset", now + 300_000, UpdateRules.rateLimitRetryAt(now, (now / 1000 + 1800).toString(), "300"))
        assertEquals("no headers → 1 h", now + 3_600_000, UpdateRules.rateLimitRetryAt(now, null, null))
        assertEquals("past reset → at least 1 min", now + 60_000, UpdateRules.rateLimitRetryAt(now, (now / 1000 - 10).toString(), null))
        assertEquals("absurd reset → at most 2 h", now + 7_200_000, UpdateRules.rateLimitRetryAt(now, (now / 1000 + 86_400).toString(), null))
        assertEquals("garbage → 1 h", now + 3_600_000, UpdateRules.rateLimitRetryAt(now, "soon", "later"))
    }

    @Test fun rateLimitMessage() {
        val now = 1_000_000L
        val m = UpdateRules.rateLimitMessage(now + 25 * 60_000, now, hasToken = false)
        assertTrue(m, m.contains("about 25 min")); assertTrue(m.contains("60 requests per hour"))
        val t = UpdateRules.rateLimitMessage(now + 30_000, now, hasToken = true)
        assertTrue(t, t.contains("about 1 min")); assertFalse(t.contains("60 requests"))
        assertTrue(UpdateRules.explainHttp(429, hasToken = false).contains("rate limit"))
    }

    @Test fun httpExplanations() {
        assertTrue(UpdateRules.explainHttp(404, hasToken = false).contains("private"))
        assertTrue(UpdateRules.explainHttp(401, hasToken = true).contains("401"))
        assertEquals("Mi5G-Dl-1.1.0-arm64.apk", UpdateRules.apkAssetName("1.1.0"))
        assertEquals("29.4 MB", UpdateRules.formatSize(30_800_000))
    }
}
