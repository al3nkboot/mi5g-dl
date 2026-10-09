package com.mvrk.vrka.mi5g.trim

import java.util.Locale

/**
 * Mi5G Dl: trim-before-download helpers (time parsing/validation, yt-dlp section args, FFmpeg fallback).
 * Times accept "ss", "mm:ss" and "hh:mm:ss" (seconds may carry decimals, e.g. "1:05.5").
 */
object TrimSpec {
    data class Range(val startSeconds: Double, val endSeconds: Double?) {
        val durationSeconds: Double? get() = endSeconds?.minus(startSeconds)
    }

    sealed interface Validation {
        data object Off : Validation
        data class Ok(val range: Range) : Validation
        data class Invalid(val message: String) : Validation
    }

    fun parseTime(value: String?): Double? {
        if (value == null) return null
        val text = value.trim()
        if (text.isEmpty() || text.length > 16) return null
        val parts = text.split(':')
        if (parts.size !in 1..3 || parts.any { it.isEmpty() }) return null
        // Only the last component may have decimals; others must be plain integers.
        parts.dropLast(1).forEach { if (!it.all(Char::isDigit)) return null }
        val last = parts.last()
        if (!Regex("""\d+(\.\d{1,3})?""").matches(last)) return null
        val numbers = parts.map { it.toDouble() }
        if (parts.size >= 2 && numbers.last() >= 60.0) return null
        if (parts.size == 3 && numbers[1] >= 60.0) return null
        return numbers.fold(0.0) { total, part -> total * 60.0 + part }
    }

    /** Validates the user's input. [durationSeconds] (if known from metadata) bounds the range. */
    fun validate(start: String, end: String, durationSeconds: Double? = null): Validation {
        if (start.isBlank() && end.isBlank()) return Validation.Off
        val s = if (start.isBlank()) 0.0 else parseTime(start)
            ?: return Validation.Invalid("Start time must be mm:ss or hh:mm:ss.")
        val e = if (end.isBlank()) null else parseTime(end)
            ?: return Validation.Invalid("End time must be mm:ss or hh:mm:ss.")
        if (e != null && e <= s) return Validation.Invalid("End time must be after start time.")
        if (durationSeconds != null && durationSeconds > 0) {
            if (s >= durationSeconds) return Validation.Invalid("Start time is beyond the media length (${format(durationSeconds)}).")
            if (e != null && e > durationSeconds + 1.0) return Validation.Invalid("End time is beyond the media length (${format(durationSeconds)}).")
        }
        if (s == 0.0 && e == null) return Validation.Off
        return Validation.Ok(Range(s, e))
    }

    fun isTrimmed(start: String, end: String): Boolean = validate(start, end) is Validation.Ok

    /** Formats seconds as m:ss or h:mm:ss (keeps tenths when present). */
    fun format(seconds: Double): String {
        val totalMillis = (seconds * 1000).toLong().coerceAtLeast(0)
        val whole = totalMillis / 1000
        val tenths = (totalMillis % 1000) / 100
        val h = whole / 3600
        val m = (whole % 3600) / 60
        val s = whole % 60
        val base = if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
        return if (tenths > 0) "$base.$tenths" else base
    }

    private fun secondsArg(seconds: Double): String =
        if (seconds == Math.floor(seconds)) seconds.toLong().toString()
        else String.format(Locale.US, "%.3f", seconds).trimEnd('0').trimEnd('.')

    /** yt-dlp `--download-sections` value, e.g. "*83-125.5" or "*10-inf". */
    fun sectionSpec(range: Range): String =
        "*${secondsArg(range.startSeconds)}-${range.endSeconds?.let(::secondsArg) ?: "inf"}"

    /** yt-dlp arguments for a trimmed download (empty when no trim is requested or input is invalid). */
    fun ytDlpArgs(start: String, end: String, precise: Boolean): List<String> {
        val range = (validate(start, end) as? Validation.Ok)?.range ?: return emptyList()
        return buildList {
            add("--download-sections"); add(sectionSpec(range))
            if (precise) add("--force-keyframes-at-cuts")
        }
    }

    /** Short human label for readouts, queue and history: "✂ 1:23–2:05" / "✂ from 0:30". */
    fun label(start: String, end: String, precise: Boolean = false): String? {
        val range = (validate(start, end) as? Validation.Ok)?.range ?: return null
        val body = if (range.endSeconds == null) "from ${format(range.startSeconds)}"
        else "${format(range.startSeconds)}–${format(range.endSeconds)}"
        return "✂ $body" + if (precise) " (precise)" else ""
    }

    /**
     * True when a finished file is clearly longer than the requested section, i.e. yt-dlp did not
     * apply the section (browser-assisted/direct transfers) and the FFmpeg fallback must trim.
     * For open-ended ranges ("from X") the source duration must be known, otherwise we stay conservative.
     */
    fun needsPostTrim(
        range: Range,
        actualDurationSeconds: Double?,
        sourceDurationSeconds: Double? = null,
        toleranceSeconds: Double = 3.0,
    ): Boolean {
        if (actualDurationSeconds == null || actualDurationSeconds <= 0) return false
        val expected = range.durationSeconds
            ?: sourceDurationSeconds?.takeIf { it > range.startSeconds }?.minus(range.startSeconds)
            ?: return false
        return actualDurationSeconds > expected + toleranceSeconds
    }

    /** FFmpeg arguments (after the binary path) for the post-download trim fallback. */
    fun ffmpegArgs(input: String, output: String, range: Range, precise: Boolean, audioOnly: Boolean): List<String> = buildList {
        addAll(listOf("-y", "-nostdin", "-hide_banner", "-v", "warning", "-progress", "pipe:1", "-nostats"))
        if (range.startSeconds > 0) { add("-ss"); add(secondsArg(range.startSeconds)) }
        add("-i"); add(input)
        range.durationSeconds?.let { add("-t"); add(secondsArg(it)) }
        addAll(listOf("-map", "0", "-map_metadata", "0"))
        if (precise && !audioOnly) {
            addAll(listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-c:s", "copy"))
        } else {
            addAll(listOf("-c", "copy", "-avoid_negative_ts", "make_zero"))
        }
        add(output)
    }
}
