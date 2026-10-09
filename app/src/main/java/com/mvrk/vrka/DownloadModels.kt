package com.mvrk.vrka

import androidx.compose.runtime.Immutable

enum class JobState {
    QUEUED,
    PREPARING,
    WAITING_FOR_USER,
    BROWSER_FALLBACK,
    DOWNLOADING,
    POSTPROCESSING,
    DONE,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == DONE || this == FAILED || this == CANCELLED

    val isForegroundWork: Boolean
        get() = this == QUEUED || this == PREPARING || this == DOWNLOADING || this == POSTPROCESSING || this == BROWSER_FALLBACK

    val label: String
        get() = when (this) {
            QUEUED -> "Queued"
            PREPARING -> "Preparing"
            WAITING_FOR_USER -> "Waiting for you"
            BROWSER_FALLBACK -> "Browser fallback"
            DOWNLOADING -> "Downloading"
            POSTPROCESSING -> "Finishing"
            DONE -> "Complete"
            FAILED -> "Failed"
            CANCELLED -> "Cancelled"
        }
}

enum class MediaMode(val label: String) {
    VIDEO("Video"),
    AUDIO("Audio"),
}

enum class VideoQuality(val label: String, val height: Int?) {
    BEST("Best available", null),
    P2160("2160p", 2160),
    P1440("1440p", 1440),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360),
}

enum class AudioFormat(val label: String, val codec: String) {
    MP3("MP3 (compressed)", "mp3"),
    OPUS("Opus (prefer native)", "opus"),
    WAV("WAV (uncompressed)", "wav"),
}

enum class SaveLocationMode(val label: String) {
    REMEMBER_LOCATION("Use selected"),
    ASK_EVERY_TIME("Ask every time"),
}

enum class FontPreference(val label: String) {
    VRKA_FONT("Mi5G Dl Font"),
    SYSTEM_FONT("System Font"),
}

enum class ThemeMode(val label: String) {
    LIGHT("Light"),
    DARK("Dark"),
}

enum class UpdatePreference(val label: String) {
    STABLE("Stable"),
    NIGHTLY("Nightly"),
}

@Immutable
data class DownloadRequest(
    val url: String,
    val mode: MediaMode = MediaMode.VIDEO,
    val quality: VideoQuality = VideoQuality.BEST,
    val prefer60Fps: Boolean = false,
    val audioFormat: AudioFormat = AudioFormat.MP3,
    val mp3Bitrate: Int = 320,
    val isPlaylist: Boolean = false,
    val playlistStart: Int? = null,
    val playlistEnd: Int? = null,
    val downloadSubtitles: Boolean = false,
    val automaticCaptions: Boolean = true,
    val embedSubtitles: Boolean = true,
    val subtitleLanguages: String = "en.*",
    val embedMetadata: Boolean = true,
    val embedThumbnail: Boolean = true,
    val sponsorBlock: Boolean = false,
    val sponsorCategories: String = "sponsor,selfpromo,interaction",
    val trimStart: String = "",
    val trimEnd: String = "",
    /** Mi5G Dl: re-encode at cut points (yt-dlp --force-keyframes-at-cuts) for frame-accurate trims. */
    val trimPrecise: Boolean = false,
    val customArguments: List<String> = emptyList(),
    val commandTemplateId: String? = null,
    val commandTemplateName: String = "",
    val commandTemplateArguments: List<String> = emptyList(),
    val h264CompatibilityMode: Boolean = false,
    val referer: String = "",
    val origin: String = "",
    val customHeaders: Map<String, String> = emptyMap(),
    val destinationTreeUri: String? = null,
    val resolvedMediaUrl: String? = null,
    val resolvedHeaders: Map<String, String> = emptyMap(),
    val resolvedHeadersOrigin: String = "",
)

@Immutable
data class DownloadJob(
    val id: String,
    val request: DownloadRequest,
    val state: JobState = JobState.QUEUED,
    val title: String = "",
    val detail: String = "Waiting",
    val progress: Float = 0f,
    val speed: String = "",
    val etaSeconds: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val outputUris: List<String> = emptyList(),
    val error: String = "",
    val attempt: Int = 1,
    val actualResolution: String? = null,
    val subtitleStatus: com.mvrk.vrka.subtitle.SubtitleStatus = com.mvrk.vrka.subtitle.SubtitleStatus.NONE,
    /** Mi5G Dl: device name when this history entry was imported by Settings & history sync. */
    val syncedFrom: String = "",
)

enum class StreamSelectionState {
    IDLE,
    RESOLVING,
    RESOLVED,
    AWAITING_CONFIRMATION,
    QUEUED,
    STARTING,
    DOWNLOADING,
    MERGING,
    COMPLETED,
    FAILED,
    CANCELLED;
}

@Immutable
data class RuntimeStatus(
    val initialized: Boolean = false,
    val busy: Boolean = false,
    val version: String = "",
    val message: String = "Runtime loads only when needed",
)
