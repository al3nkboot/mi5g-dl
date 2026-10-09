package com.mvrk.vrka.mi5g.youtube

import android.content.Context
import java.io.File

/**
 * Mi5G Dl: the YouTube cookies.txt lives in app-private no-backup storage (excluded from Android
 * backups), is never synced, never logged and only passed to yt-dlp for YouTube URLs.
 */
class YouTubeCookieStore(private val dir: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "mi5g"))

    val file: File get() = File(dir, "youtube_cookies.txt")

    fun exists(): Boolean = file.isFile && file.length() > NetscapeCookies.HEADER.length

    /** Path for yt-dlp, or null when the user is not signed in. */
    fun pathIfPresent(): String? = if (exists()) file.absolutePath else null

    fun savedAt(): Long = if (exists()) file.lastModified() else 0L

    fun entryCount(): Int = runCatching { if (exists()) NetscapeCookies.countEntries(file.readText()) else 0 }.getOrDefault(0)

    /** Writes atomically with owner-only permissions. Returns the number of cookies written. */
    @Synchronized
    fun write(cookies: List<NetscapeCookies.Cookie>, nowSeconds: Long = System.currentTimeMillis() / 1000): Int {
        val clean = NetscapeCookies.sanitize(cookies, nowSeconds)
        require(clean.isNotEmpty()) { "No YouTube/Google cookies to save" }
        dir.mkdirs()
        val tmp = File(dir, "youtube_cookies.txt.tmp")
        tmp.writeText(NetscapeCookies.format(clean, nowSeconds))
        ownerOnly(tmp)
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        ownerOnly(file)
        return clean.size
    }

    @Synchronized
    fun clear(): Boolean {
        val f = file
        if (!f.exists()) return true
        // Overwrite before deleting so the session cookies don't linger in free blocks of the file.
        runCatching { f.writeText(" ".repeat(f.length().coerceAtMost(256_000L).toInt())) }
        return f.delete()
    }

    private fun ownerOnly(f: File) {
        f.setReadable(false, false); f.setReadable(true, true)
        f.setWritable(false, false); f.setWritable(true, true)
        f.setExecutable(false, false)
    }
}
