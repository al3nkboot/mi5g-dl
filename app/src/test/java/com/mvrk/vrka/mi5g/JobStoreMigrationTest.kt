package com.mvrk.vrka.mi5g

import com.mvrk.vrka.AudioFormat
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.JobState
import com.mvrk.vrka.JobStore
import com.mvrk.vrka.MediaMode
import com.mvrk.vrka.VideoQuality
import com.mvrk.vrka.mi5g.sync.SyncManager
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JobStoreMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun job(id: String = "j1") = DownloadJob(
        id = id,
        request = DownloadRequest(url = "https://youtu.be/abc", mode = MediaMode.VIDEO, quality = VideoQuality.P720, audioFormat = AudioFormat.MP3,
            trimStart = "1:00", trimEnd = "1:30", trimPrecise = true),
        state = JobState.DONE, title = "T", createdAt = 1000, updatedAt = 2000,
        outputUris = listOf("content://media/1"), syncedFrom = "Pixel",
    )

    @Test fun roundTripWritesSchemaAndNewFields() {
        val f = File(tmp.root, "jobs.json")
        JobStore(f).save(listOf(job()))
        val raw = JSONArray(f.readText()).getJSONObject(0)
        assertEquals(JobStore.SCHEMA_VERSION, raw.getInt("schema"))
        val back = JobStore(f).load().single()
        assertTrue(back.request.trimPrecise)
        assertEquals("Pixel", back.syncedFrom)
        assertEquals("1:00", back.request.trimStart)
    }

    @Test fun legacyV1RecordIsMigrated() {
        val f = File(tmp.root, "jobs.json")
        JobStore(f).save(listOf(job()))
        val arr = JSONArray(f.readText())
        val o = arr.getJSONObject(0)
        o.remove("schema"); o.remove("syncedFrom"); o.optJSONObject("request")?.remove("trimPrecise")
        f.writeText(arr.toString())

        val back = JobStore(f).load().single()
        assertFalse(back.request.trimPrecise)
        assertEquals("", back.syncedFrom)
        assertEquals("1:00", back.request.trimStart)
        assertEquals(listOf("content://media/1"), back.outputUris)
        // file rewritten at the new schema
        assertEquals(JobStore.SCHEMA_VERSION, JSONArray(f.readText()).getJSONObject(0).getInt("schema"))
    }

    @Test fun migrateIsIdempotent() {
        val o = org.json.JSONObject().put("request", org.json.JSONObject())
        JobStore.migrate(o); JobStore.migrate(o)
        assertEquals(2, o.getInt("schema")); assertEquals(false, o.getJSONObject("request").getBoolean("trimPrecise"))
    }

    @Test fun syncRecordHasNoFileUrisAndMapsBack() {
        val r = SyncManager.toRecord(job().copy(syncedFrom = ""), "Galaxy")
        assertEquals("Galaxy", r.device)
        val j = SyncManager.toJob(r, "Pixel")
        assertTrue(j.outputUris.isEmpty())
        assertEquals("Galaxy", j.syncedFrom)
        assertEquals(VideoQuality.P720, j.request.quality)
        assertTrue(j.request.trimPrecise)
        assertEquals("", SyncManager.toJob(r, "Galaxy").syncedFrom)
        // an active state from a remote phone never becomes a running job here
        assertEquals(JobState.FAILED, SyncManager.toJob(r.copy(state = "DOWNLOADING"), "Pixel").state)
    }
}
