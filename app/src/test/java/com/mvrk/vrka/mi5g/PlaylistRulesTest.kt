package com.mvrk.vrka.mi5g

import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.JobState
import com.mvrk.vrka.JobStore
import com.mvrk.vrka.mi5g.playlist.PlaylistRules
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaylistRulesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun detectsPlaylistUrls() {
        assertTrue(PlaylistRules.looksLikePlaylist("https://www.youtube.com/playlist?list=PL123abc"))
        assertTrue(PlaylistRules.looksLikePlaylist("https://www.youtube.com/watch?v=abc&list=PL123abc"))
        assertTrue(PlaylistRules.looksLikePlaylist("https://music.youtube.com/playlist?list=OLAK5uy_x"))
        assertTrue(PlaylistRules.looksLikePlaylist("https://soundcloud.com/artist/sets/best-of"))
        assertTrue(PlaylistRules.looksLikePlaylist("https://artist.bandcamp.com/album/first"))
        assertFalse(PlaylistRules.looksLikePlaylist("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(PlaylistRules.looksLikePlaylist("https://youtu.be/dQw4w9WgXcQ"))
        assertFalse(PlaylistRules.looksLikePlaylist("https://x.com/u/status/1"))
        assertFalse(PlaylistRules.looksLikePlaylist("ftp://example.com/playlist"))
    }

    @Test fun parsesFlatPlaylistJson() {
        val json = """{"title":"My Mix","entries":[
            {"ie_key":"Youtube","id":"aaa","url":"https://www.youtube.com/watch?v=aaa","title":"One","thumbnails":[{"url":"https://i.ytimg.com/vi/aaa/hq.jpg"}]},
            {"ie_key":"Youtube","id":"bbb","title":"Two"},
            {"id":"ccc","title":"no url, unknown site"},
            null
        ]}"""
        val p = PlaylistRules.parse(json)
        assertEquals("My Mix", p.title)
        assertEquals(listOf("https://www.youtube.com/watch?v=aaa", "https://www.youtube.com/watch?v=bbb"), p.entries.map { it.url })
        assertEquals("https://i.ytimg.com/vi/aaa/hq.jpg", p.entries[0].thumbnail)
        assertNull(p.entries[1].thumbnail)
    }

    @Test fun singleVideoJsonBecomesOneEntry() {
        val p = PlaylistRules.parse("""{"title":"Solo","webpage_url":"https://youtu.be/x"}""")
        assertEquals(1, p.entries.size)
    }

    @Test fun folderNameIsSafe() {
        assertEquals("AC_DC _ Hits_", PlaylistRules.folderName("AC/DC : Hits?"))
        assertEquals("Playlist", PlaylistRules.folderName("  ..  "))
        assertEquals("قائمتي", PlaylistRules.folderName("قائمتي"))
    }

    private fun job(id: String, url: String, state: JobState, at: Long, group: String = "g") =
        DownloadJob(id = id, request = DownloadRequest(url = url, playlistGroup = group, playlistFolder = "F"), state = state, createdAt = at)

    @Test fun progressCountsLatestJobPerItemAndKeepsGoingAfterFailure() {
        val jobs = listOf(
            job("1", "u1", JobState.DONE, 1),
            job("2", "u2", JobState.FAILED, 2),
            job("3", "u3", JobState.DOWNLOADING, 3),
            job("4", "u4", JobState.QUEUED, 4),
            job("x", "other", JobState.FAILED, 5, group = "other"),
        )
        val p = PlaylistRules.progress(jobs, "g")
        assertEquals(4, p.total); assertEquals(1, p.done); assertEquals(listOf("2"), p.failed.map { it.id })
        assertEquals("3", p.current?.id); assertFalse(p.isComplete)

        // Retry of u2 replaces the failure; the others finish.
        val later = jobs.map { if (it.id == "3" || it.id == "4") it.copy(state = JobState.DONE) else it } + job("2r", "u2", JobState.DONE, 9)
        val q = PlaylistRules.progress(later, "g")
        assertEquals(4, q.done); assertTrue(q.failed.isEmpty()); assertTrue(q.isComplete); assertNull(q.current)
    }

    @Test fun playlistFieldsPersistAndOldRecordsLoadWithDefaults() {
        val f = File(tmp.root, "jobs.json")
        JobStore(f).save(listOf(job("1", "u1", JobState.DONE, 1)))
        assertEquals("F", JobStore(f).load().single().request.playlistFolder)
        // 1.1.2 record: no playlist fields.
        val arr = JSONArray(f.readText()); arr.getJSONObject(0).getJSONObject("request").apply { remove("playlistGroup"); remove("playlistFolder") }
        f.writeText(arr.toString())
        val back = JobStore(f).load().single()
        assertEquals("", back.request.playlistGroup); assertEquals("", back.request.playlistFolder)
    }
}
