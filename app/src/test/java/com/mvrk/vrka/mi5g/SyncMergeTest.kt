package com.mvrk.vrka.mi5g

import com.mvrk.vrka.mi5g.sync.HistoryRecord
import com.mvrk.vrka.mi5g.sync.SettingValue
import com.mvrk.vrka.mi5g.sync.SettingsStamps
import com.mvrk.vrka.mi5g.sync.SyncCodec
import com.mvrk.vrka.mi5g.sync.SyncFormatException
import com.mvrk.vrka.mi5g.sync.SyncMerge
import com.mvrk.vrka.mi5g.sync.SyncPolicy
import com.mvrk.vrka.mi5g.sync.SyncSnapshot
import com.mvrk.vrka.mi5g.sync.TombstoneStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncMergeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun rec(id: String, t: Long, url: String = "https://youtu.be/$id", created: Long = t, title: String = id) =
        HistoryRecord(id = id, updatedAt = t, url = url, title = title, createdAt = created)

    // ---------------------------------------------------------------- settings LWW
    @Test fun settingsLastWriterWins() {
        val local = mapOf("default_quality" to SettingValue("P1080", 100), "amoled" to SettingValue(true, 300))
        val remote = mapOf("default_quality" to SettingValue("P720", 200), "amoled" to SettingValue(false, 250))
        val (merged, apply) = SyncMerge.mergeSettings(local, remote)
        assertEquals("P720", merged["default_quality"]!!.value)
        assertEquals(true, merged["amoled"]!!.value)
        assertEquals(mapOf<String, Any>("default_quality" to "P720"), apply)
    }

    @Test fun settingsTieKeepsLocalAndRemoteOnlyKeysApply() {
        val (merged, apply) = SyncMerge.mergeSettings(
            mapOf("theme_mode" to SettingValue("DARK", 100)),
            mapOf("theme_mode" to SettingValue("LIGHT", 100), "share_skip_sheet" to SettingValue(true, 5)),
        )
        assertEquals("DARK", merged["theme_mode"]!!.value)
        assertEquals(mapOf<String, Any>("share_skip_sheet" to true), apply)
    }

    @Test fun secretsAndDevicePathsNeverMerged() {
        val remote = mapOf(
            "github_token" to SettingValue("github_pat_x", 999), "output_tree_uri" to SettingValue("content://x", 999),
            "cookies" to SettingValue("a=b", 999), "browser_session" to SettingValue("s", 999),
        )
        val (merged, apply) = SyncMerge.mergeSettings(emptyMap(), remote)
        assertTrue(merged.isEmpty()); assertTrue(apply.isEmpty())
    }

    @Test fun policyExcludesSecrets() {
        listOf("github_token", "token", "cookies", "browser_session", "custom_headers", "authorization",
            "output_tree_uri", "save_location_mode", "is_download_location_configured", "sync_file_uri", "my_password").forEach {
            assertFalse(it, SyncPolicy.isSyncable(it))
        }
        assertTrue(SyncPolicy.isSyncable("default_quality"))
        SyncPolicy.SYNCED_SETTINGS.keys.forEach { assertTrue(it, SyncPolicy.isSyncable(it)) }
        assertTrue(SyncPolicy.valueLooksSecret("--cookies /sdcard/c.txt"))
        assertTrue(SyncPolicy.valueLooksSecret("""[{"args":"--add-header Authorization: Bearer x"}]"""))
        assertFalse(SyncPolicy.valueLooksSecret("""[{"args":"--limit-rate 2M"}]"""))
    }

    @Test fun codecExcludesSecretsAndRoundTrips() {
        val snap = SyncSnapshot(
            settings = mapOf(
                "default_quality" to SettingValue("P720", 5),
                "github_token" to SettingValue("github_pat_SECRET", 5),
                "output_tree_uri" to SettingValue("content://tree/primary", 5),
                "custom_command_templates" to SettingValue("""[{"a":"--cookies c.txt"}]""", 5),
                "max_concurrent_downloads" to SettingValue(3, 7),
            ),
            history = mapOf("a" to rec("a", 10), "b" to HistoryRecord.tombstone("b", 20)),
            fileId = "file-1", updatedAt = 30,
        )
        val text = SyncCodec.encode(snap)
        assertFalse(text.contains("SECRET")); assertFalse(text.contains("github_token"))
        assertFalse(text.contains("content://")); assertFalse(text.contains("cookies"))
        val back = SyncCodec.decode(text)
        assertEquals(setOf("default_quality", "max_concurrent_downloads"), back.settings.keys)
        assertEquals(3, back.settings["max_concurrent_downloads"]!!.value)
        assertEquals(snap.history, back.history)
        assertEquals("file-1", back.fileId)
    }

    @Test fun decodeRejectsForeignFilesAndWrongTypes() {
        assertEquals(SyncSnapshot(), SyncCodec.decode(""))
        listOf("{nope" to "not_json", """{"format":"masarifi"}""" to "wrong_format", """{"format":"mi5gdl-sync","version":99}""" to "newer_version").forEach { (t, code) ->
            try { SyncCodec.decode(t); fail(t) } catch (e: SyncFormatException) { assertEquals(code, e.code) }
        }
        val bad = JSONObject().put("format", "mi5gdl-sync").put("version", 1)
            .put("settings", JSONObject().put("amoled", JSONObject().put("v", "yes").put("t", 1))
                .put("github_token", JSONObject().put("v", "x").put("t", 1)))
        assertTrue(SyncCodec.decode(bad.toString()).settings.isEmpty())
    }

    // ---------------------------------------------------------------- history
    @Test fun historyUnionAndLww() {
        val local = SyncSnapshot(history = mapOf("a" to rec("a", 10, title = "old"), "l" to rec("l", 5)))
        val remote = SyncSnapshot(history = mapOf("a" to rec("a", 20, title = "new"), "r" to rec("r", 7)), fileId = "f")
        val res = SyncMerge.merge(local, remote)
        assertEquals(setOf("a", "l", "r"), res.merged.history.keys)
        assertEquals("new", res.merged.history["a"]!!.title)
        assertEquals(setOf("a", "r"), res.historyUpserts.map { it.id }.toSet())
        assertTrue(res.historyDeletes.isEmpty())
        assertTrue(SyncMerge.needsPush(res.merged, remote))
    }

    @Test fun tombstoneDeletesLocalAndIsNotResurrected() {
        val local = SyncSnapshot(history = mapOf("a" to rec("a", 10)))
        val remote = SyncSnapshot(history = mapOf("a" to HistoryRecord.tombstone("a", 50)), fileId = "f")
        val res = SyncMerge.merge(local, remote)
        assertEquals(setOf("a"), res.historyDeletes)
        assertTrue(res.merged.history["a"]!!.deleted)

        // local tombstone vs older remote live entry → stays deleted, nothing to upsert
        val res2 = SyncMerge.merge(SyncSnapshot(history = mapOf("a" to HistoryRecord.tombstone("a", 50))), SyncSnapshot(history = mapOf("a" to rec("a", 10))))
        assertTrue(res2.merged.history["a"]!!.deleted)
        assertTrue(res2.historyUpserts.isEmpty())
        // equal times → tombstone wins
        assertTrue(SyncMerge.pick(rec("a", 50), HistoryRecord.tombstone("a", 50)).deleted)
        // edit after deletion wins
        assertFalse(SyncMerge.pick(rec("a", 60), HistoryRecord.tombstone("a", 50)).deleted)
    }

    @Test fun dedupeByUrlAndTimestampConverges() {
        val url = "https://youtu.be/same"
        val phoneA = SyncSnapshot(history = mapOf("aaa" to rec("aaa", 1000, url, created = 1000)))
        val phoneB = SyncSnapshot(history = mapOf("zzz" to rec("zzz", 1500, url, created = 1500)))
        // B syncs first (file empty → file has zzz), then A syncs with the file.
        val file1 = SyncMerge.merge(phoneB, SyncSnapshot()).merged
        val a = SyncMerge.merge(phoneA, file1)
        assertEquals(mapOf("zzz" to "aaa"), a.aliases)
        assertTrue(a.historyUpserts.all { it.id == "aaa" }); assertTrue(a.historyDeletes.isEmpty())
        assertTrue(a.merged.history["zzz"]!!.deleted); assertFalse(a.merged.history["aaa"]!!.deleted)
        // B syncs again: its zzz is tombstoned remotely and aaa arrives → one live entry on both phones.
        val b = SyncMerge.merge(phoneB, a.merged)
        assertEquals(setOf("zzz"), b.historyDeletes)
        assertEquals(listOf("aaa"), b.historyUpserts.map { it.id })
        assertEquals(a.merged.history.filterValues { !it.deleted }.keys, b.merged.history.filterValues { !it.deleted }.keys)
    }

    @Test fun dedupeRenamesLocalWhenRemoteIdIsSmaller() {
        val url = "https://x.com/u/status/1"
        val local = SyncSnapshot(history = mapOf("mmm" to rec("mmm", 10, url, created = 1000)))
        val remote = SyncSnapshot(history = mapOf("bbb" to rec("bbb", 10, url, created = 1001)))
        val res = SyncMerge.merge(local, remote)
        assertEquals(setOf("mmm"), res.historyDeletes)
        assertEquals(listOf("bbb"), res.historyUpserts.map { it.id })
        assertTrue(res.merged.history["mmm"]!!.deleted)
    }

    @Test fun differentTimesAreNotDeduped() {
        val url = "https://youtu.be/same"
        val res = SyncMerge.merge(
            SyncSnapshot(history = mapOf("a" to rec("a", 1, url, created = 1_000))),
            SyncSnapshot(history = mapOf("b" to rec("b", 1, url, created = 60_000))),
        )
        assertTrue(res.aliases.isEmpty()); assertEquals(2, res.merged.history.values.count { !it.deleted })
    }

    @Test fun noPushWhenEqual() {
        val s = SyncSnapshot(settings = mapOf("amoled" to SettingValue(true, 1)), history = mapOf("a" to rec("a", 1)), fileId = "f")
        val res = SyncMerge.merge(s.copy(fileId = null), s)
        assertFalse(res.changedLocal)
        assertFalse(SyncMerge.needsPush(res.merged, s))
    }

    @Test fun pruneOldTombstones() {
        val day = 24 * 3600 * 1000L
        val s = SyncSnapshot(history = mapOf("old" to HistoryRecord.tombstone("old", 0), "new" to HistoryRecord.tombstone("new", 199 * day), "live" to rec("live", 0)))
        assertEquals(setOf("new", "live"), SyncMerge.pruneTombstones(s, now = 200 * day).history.keys)
    }

    // ---------------------------------------------------------------- stamps
    @Test fun settingsStamps() {
        val first = SettingsStamps.stamp(SettingsStamps.State(emptyMap(), emptyMap()), mapOf("amoled" to true, "github_token" to "x"), now = 100)
        assertEquals(mapOf("amoled" to 0L), first.stamps) // first run: older than any real edit
        val same = SettingsStamps.stamp(first, mapOf("amoled" to true), now = 200)
        assertEquals(0L, same.stamps["amoled"])
        val changed = SettingsStamps.stamp(same, mapOf("amoled" to false, "theme_mode" to "LIGHT"), now = 300)
        assertEquals(300L, changed.stamps["amoled"]); assertEquals(300L, changed.stamps["theme_mode"])
        val snap = SettingsStamps.snapshot(SettingsStamps.State(mapOf("custom_command_templates" to "--cookies a", "amoled" to true), mapOf("amoled" to 3L)))
        assertEquals(setOf("amoled"), snap.keys)
    }

    @Test fun tombstoneStoreKeepsEarliestAndPrunes() {
        val store = TombstoneStore(tmp.newFile("t.json").also { it.delete() })
        store.record(mapOf("a" to 50L, "b" to 10L))
        store.record(mapOf("a" to 70L))
        assertEquals(mapOf("a" to 50L, "b" to 10L), store.all())
        store.prune(olderThan = 20L)
        assertEquals(setOf("a"), store.all().keys)
        store.remove(listOf("a"))
        assertTrue(store.all().isEmpty())
    }
}
