package com.mvrk.vrka.mi5g.playlist

import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.JobState
import org.json.JSONObject

/** Mi5G Dl 1.2.0: playlist detection, yt-dlp `--flat-playlist -J` parsing and batch progress (pure, unit-tested). */
object PlaylistRules {
    data class Entry(val url: String, val title: String, val thumbnail: String?)
    data class Playlist(val title: String, val entries: List<Entry>)
    data class Progress(val total: Int, val done: Int, val failed: List<DownloadJob>, val current: DownloadJob?) {
        val finished get() = done + failed.size
        val isComplete get() = total > 0 && finished == total
    }

    private val hints = listOf(
        Regex("""[?&]list=[\w-]+"""),                        // YouTube / YouTube Music
        Regex("""/playlist\b""", RegexOption.IGNORE_CASE),   // YouTube, Dailymotion, generic
        Regex("""soundcloud\.com/[^/]+/sets/"""),
        Regex("""bandcamp\.com/album/"""),
        Regex("""vimeo\.com/(showcase|album)/"""),
    )

    /**
     * Cheap URL-only check that decides whether to offer the playlist screen.
     * shortcut: URL heuristics only; other yt-dlp playlist sites are still caught when the user opens the list manually.
     */
    fun looksLikePlaylist(url: String): Boolean = url.startsWith("http") && hints.any { it.containsMatchIn(url) }

    fun parse(json: String): Playlist {
        val o = JSONObject(json)
        val arr = o.optJSONArray("entries")
        val entries = buildList {
            if (arr == null) {
                // A single video: yt-dlp returns it without "entries".
                (o.optString("webpage_url").ifBlank { o.optString("original_url") }).takeIf { it.startsWith("http") }
                    ?.let { add(Entry(it, o.optString("title", it), o.optString("thumbnail").ifBlank { null })) }
            } else for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val url = entryUrl(e) ?: continue
                val thumbs = e.optJSONArray("thumbnails")
                val thumb = e.optString("thumbnail").ifBlank { null }
                    ?: thumbs?.optJSONObject(0)?.optString("url")?.ifBlank { null }
                add(Entry(url, e.optString("title").ifBlank { url }, thumb))
            }
        }
        return Playlist(o.optString("title").ifBlank { "Playlist" }, entries)
    }

    private fun entryUrl(e: JSONObject): String? {
        e.optString("url").takeIf { it.startsWith("http") }?.let { return it }
        e.optString("webpage_url").takeIf { it.startsWith("http") }?.let { return it }
        val id = e.optString("id")
        return if (id.isNotBlank() && e.optString("ie_key").equals("Youtube", true)) "https://www.youtube.com/watch?v=$id" else null
    }

    /** Folder-safe playlist name (Android forbids these characters in file names). */
    fun folderName(title: String): String =
        title.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trim('.').take(80).ifBlank { "Playlist" }

    /** Batch progress; a retried item replaces its earlier failed job (latest job per URL wins). */
    fun progress(jobs: List<DownloadJob>, group: String): Progress {
        val latest = jobs.filter { it.request.playlistGroup == group }
            .groupBy { it.request.url }.values.map { list -> list.maxBy { it.createdAt } }
        return Progress(
            total = latest.size,
            done = latest.count { it.state == JobState.DONE },
            failed = latest.filter { it.state == JobState.FAILED || it.state == JobState.CANCELLED },
            current = latest.filterNot { it.state.isTerminal }.minByOrNull { if (it.state == JobState.QUEUED) 1 else 0 },
        )
    }
}
