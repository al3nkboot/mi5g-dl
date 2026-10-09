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

    private fun asset(id: Long, name: String, size: Long = 1000, url: String = "$assetBase$id", digest: String? = "sha256:$sha") =
        JSONObject().put("id", id).put("name", name).put("size", size).put("url", url).putOpt("digest", digest)
            .put("browser_download_url", "https://github.com/al3nkboot/mi5g-dl/releases/download/x/$name")

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

    @Test fun httpExplanations() {
        assertTrue(UpdateRules.explainHttp(404, hasToken = false).contains("private"))
        assertTrue(UpdateRules.explainHttp(401, hasToken = true).contains("401"))
        assertEquals("Mi5G-Dl-1.1.0-arm64.apk", UpdateRules.apkAssetName("1.1.0"))
        assertEquals("29.4 MB", UpdateRules.formatSize(30_800_000))
    }
}
