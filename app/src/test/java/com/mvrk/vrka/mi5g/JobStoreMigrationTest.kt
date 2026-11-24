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
        val root = org.json.JSONObject(f.readText())
        assertEquals(JobStore.CURRENT_SCHEMA_VERSION, root.getInt("schemaVersion"))
        val back = JobStore(f).load().single()
        assertTrue(back.request.trimPrecise)
        assertEquals("Pixel", back.syncedFrom)
        assertEquals("1:00", back.request.trimStart)
    }

    @Test fun legacyV1RecordIsMigrated() {
        val f = File(tmp.root, "jobs.json")
        JobStore(f).save(listOf(job()))
        val arr = org.json.JSONObject(f.readText()).getJSONArray("jobs")
        val o = arr.getJSONObject(0)
        o.remove("schema"); o.remove("syncedFrom"); o.optJSONObject("request")?.remove("trimPrecise")
        f.writeText(arr.toString()) // legacy v1 was a bare array; JobStore still imports it

        val back = JobStore(f).load().single()
        assertFalse(back.request.trimPrecise)
        assertEquals("", back.syncedFrom)
        assertEquals("1:00", back.request.trimStart)
        assertEquals(listOf("content://media/1"), back.outputUris)
        // JobStore.load never rewrites the file; the bare array stays until Room migration/save.
        val persisted = f.readText().trim()
        assertTrue(persisted.startsWith("["))
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
