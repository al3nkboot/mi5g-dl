package com.mvrk.vrka

import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

internal object DownloadRequestFactory {
    private const val outputMarker = "__VRKA_OUTPUT__"
    private var nativeLibraryDir: String? = null
    @Volatile
    private var impersonationSupported: Boolean = false

    fun initNativeLibraryDir(dir: String?) {
        nativeLibraryDir = dir
    }

    fun isImpersonationSupported(): Boolean = impersonationSupported

    fun setImpersonationSupported(supported: Boolean) {
        impersonationSupported = supported
    }

    internal fun shouldAttemptBrowserFallback(
        request: DownloadRequest,
        hasFailure: Boolean,
        cancelled: Boolean,
    ): Boolean = hasFailure && request.resolvedMediaUrl == null && !cancelled

    internal fun browserFallbackRequest(
        request: DownloadRequest,
        mediaUrl: String,
        headers: Map<String, String>,
    ): DownloadRequest {
        require(CustomCommandPolicy.isHttpUrl(mediaUrl)) { "Browser fallback candidate must be a valid http or https URL." }
        return request.copy(
            // Keep the public page as the job's user-facing source. The private candidate is only
            // an internal transfer input and must not inherit custom command/header state.
            customArguments = emptyList(),
            commandTemplateId = null,
            commandTemplateName = "",
            commandTemplateArguments = emptyList(),
            referer = "",
            origin = "",
            customHeaders = emptyMap(),
            resolvedMediaUrl = mediaUrl,
            resolvedHeaders = headers,
            resolvedHeadersOrigin = HeaderValidation.originOf(mediaUrl).orEmpty(),
        )
    }

    fun getQuickJsPath(): String? {
        val f = nativeLibraryDir?.let { File(it, "libqjs.so") }
        return if (f != null && f.exists()) f.absolutePath else null
    }

    private fun addJsRuntime(request: YoutubeDLRequest) {
        val qjsPath = getQuickJsPath()
        if (qjsPath != null) {
            request.addOption("--js-runtimes", "quickjs:$qjsPath")
        }
        request.addOption("--remote-components", "ejs:github")
    }

    /**
     * Mi5G Dl: per-attempt YouTube context: the optional signed-in cookies.txt and an alternative
     * InnerTube `player_client` plan used when YouTube's bot check rejects the default clients.
     */
    data class YouTubeContext(
        val cookieFile: String? = null,
        val plan: com.mvrk.vrka.mi5g.youtube.YouTubeRecovery.ClientPlan? = null,
    )

    private fun YoutubeDLRequest.addYouTubeContext(request: DownloadRequest, yt: YouTubeContext?) {
        if (yt == null || request.resolvedMediaUrl != null) return
        if (!com.mvrk.vrka.mi5g.youtube.YouTubeRecovery.isYouTubeUrl(request.url)) return
        com.mvrk.vrka.mi5g.youtube.YouTubeRecovery.cookiesArgs(request.url, request.resolvedMediaUrl, yt.cookieFile)
            .takeIf { it.size == 2 }?.let { addOption(it[0], it[1]) }
        com.mvrk.vrka.mi5g.youtube.YouTubeRecovery.extractorArgs(yt.plan)
            .takeIf { it.size == 2 }?.let { addOption(it[0], it[1]) }
    }

    fun info(request: DownloadRequest, youtube: YouTubeContext? = null): YoutubeDLRequest =
        YoutubeDLRequest(request.resolvedMediaUrl ?: request.url).apply {
            addOption("--no-warnings")
            addOption("--legacy-server-connect")
            if (!request.isPlaylist) addOption("--no-playlist")
            addJsRuntime(this)
            addSessionContext(request)
            addYouTubeContext(request, youtube)
        }

    fun download(
        job: DownloadJob,
        stagingDirectory: File,
        recoveryAttempt: Boolean = false,
        includeSubtitles: Boolean = true,
        youtube: YouTubeContext? = null,
    ): YoutubeDLRequest {
        val options = job.request
        val source = options.resolvedMediaUrl ?: options.url
        if (!options.commandTemplateId.isNullOrBlank()) {
            return customCommand(job, stagingDirectory)
        }
        return YoutubeDLRequest(source).apply {
            addOption("--newline")
            addOption("--progress")
            addOption("--no-mtime")
            addOption("--no-overwrites")
            addOption("--legacy-server-connect")
            addOption("--concurrent-fragments", "4")
            addOption("--print", "before_dl:__VRKA_TITLE__%(title)s")
            addOption("--print", "after_move:$outputMarker%(filepath)s")
            val stagingTemplate = if (options.isPlaylist) {
                "%(playlist_index|0)03d-%(title).96B.%(ext)s"
            } else {
                "media.%(ext)s"
            }
            addOption("-o", File(stagingDirectory, stagingTemplate).absolutePath)

            if (options.isPlaylist) {
                options.playlistStart?.let { addOption("--playlist-start", it) }
                options.playlistEnd?.let { addOption("--playlist-end", it) }
            } else {
                addOption("--no-playlist")
            }

            if (options.mode == MediaMode.AUDIO) {
                val format = when (options.audioFormat) {
                    AudioFormat.OPUS -> "bestaudio[acodec^=opus]/bestaudio/best"
                    else -> "bestaudio/best"
                }
                addOption("-f", format)
                addOption("--extract-audio")
                addOption("--audio-format", options.audioFormat.codec)
                if (options.audioFormat == AudioFormat.MP3) {
                    val bitrate = options.mp3Bitrate.coerceIn(128, 320)
                    addOption("--audio-quality", "${bitrate}K")
                }
                if (options.embedThumbnail && options.audioFormat != AudioFormat.WAV) addOption("--embed-thumbnail")
            } else {
                val format = buildVideoFormat(options.quality.height, options.prefer60Fps)
                addOption("-f", format)
                if (options.prefer60Fps) {
                    addOption("-S", "res,fps")
                }
                addOption("--merge-output-format", "mp4")
            }

            if (options.embedMetadata) addOption("--embed-metadata")
            val effectiveIncludeSubtitles = includeSubtitles && options.resolvedMediaUrl == null
            if (effectiveIncludeSubtitles && options.downloadSubtitles) {
                addOption("--write-subs")
                if (options.automaticCaptions) {
                    addOption("--write-auto-subs")
                    val subLangs = if (options.subtitleLanguages.isBlank() || options.subtitleLanguages == "en.*") {
                        "en,en-orig,en-US,en-GB"
                    } else {
                        options.subtitleLanguages
                    }
                    addOption("--sub-langs", subLangs)
                    addOption("--sleep-subtitles", "2")
                } else {
                    val subLangs = if (options.subtitleLanguages.isBlank()) {
                        "en.*,en,en-US,en-GB"
                    } else {
                        options.subtitleLanguages
                    }
                    addOption("--sub-langs", subLangs)
                    addOption("--sleep-subtitles", "1")
                }
                addOption("--sub-format", "srt/best")
                if (options.mode == MediaMode.VIDEO && options.embedSubtitles) {
                    addOption("--embed-subs")
                }
            }
            if (options.sponsorBlock) {
                addOption(
                    "--sponsorblock-remove",
                    options.sponsorCategories.ifBlank { "sponsor" },
                )
            }

            // Mi5G Dl: trim before download (section download; precise cut re-encodes at keyframes).
            com.mvrk.vrka.mi5g.trim.TrimSpec.ytDlpArgs(options.trimStart, options.trimEnd, options.trimPrecise).let { args ->
                var i = 0
                while (i < args.size) {
                    if (args[i] == "--download-sections" && i + 1 < args.size) { addOption(args[i], args[i + 1]); i += 2 }
                    else { addOption(args[i]); i += 1 }
                }
            }

            addJsRuntime(this)
            val effectiveHeaders = HeaderValidation.resolveEffectiveHeaders(options)
            if (recoveryAttempt || options.resolvedMediaUrl != null) {
                if (effectiveHeaders.keys.none { it.equals("User-Agent", true) }) {
                    addOption("--user-agent", DESKTOP_USER_AGENT)
                }
                if (effectiveHeaders.keys.none { it.equals("Referer", true) }) {
                    addOption("--referer", options.url)
                }
            }
            if (recoveryAttempt && isImpersonationSupported()) {
                addOption("--extractor-args", "generic:impersonate")
            }
            addSessionContext(options)
            addYouTubeContext(options, youtube)
            if (options.customArguments.isNotEmpty()) {
                addCommands(safeCustomArguments(options).take(40))
            }
        }
    }

    internal fun customCommand(job: DownloadJob, stagingDirectory: File): YoutubeDLRequest {
        val options = job.request
        require(CustomCommandPolicy.isHttpUrl(options.url)) { "The custom-command URL must be a valid http or https URL." }
        val validation = CustomCommandPolicy.validateTemplate(
            options.commandTemplateName,
            options.commandTemplateArguments,
        )
        require(validation == null) { validation.orEmpty() }

        val outputTemplate = "%(playlist_index|0)03d-%(title).96B.%(ext)s"
        return YoutubeDLRequest(listOf(options.url)).apply {
            addOption("--ignore-config")
            addOption("--no-mtime")
            addOption("--no-overwrites")
            addOption("-o", File(stagingDirectory, outputTemplate).absolutePath)
            addOption("--print", "before_dl:__VRKA_TITLE__%(title)s")
            addCommands(options.commandTemplateArguments)
            // VRKA-owned output/progress settings are appended after user arguments.
            addOption("--newline")
            addOption("--progress")
        }
    }

    fun outputPaths(responseText: String): List<String> =
        responseText.lineSequence()
            .map(String::trim)
            .filter { it.startsWith(outputMarker) }
            .map { it.removePrefix(outputMarker).trim() }
            .filter(String::isNotEmpty)
            .distinct()
            .toList()

    private fun YoutubeDLRequest.addSessionContext(request: DownloadRequest) {
        val effectiveHeaders = HeaderValidation.resolveEffectiveHeaders(request)
        effectiveHeaders.forEach { (name, value) ->
            addCommands(listOf("--add-header", "$name:$value"))
        }
    }

    private fun safeCustomArguments(request: DownloadRequest): List<String> {
        val optionsWithValues = setOf(
            "-f",
            "--format",
            "--audio-format",
            "--audio-quality",
            "--merge-output-format",
            "--remux-video",
            "--recode-video",
            "--impersonate",
        )
        val dangerousOptionsWithValues = setOf(
            "-o", "--output", "--paths", "--exec", "--exec-before-download",
            "--external-downloader", "--external-downloader-args", "--config-location",
            "--config-locations", "--plugin-dirs", "--load-info-json", "--batch-file",
            "--ffmpeg-location", "--print-to-file",
        )
        val flags = setOf("-x", "--extract-audio")
        val result = mutableListOf<String>()
        var skipNext = false
        request.customArguments.forEach { argument ->
            if (skipNext) {
                skipNext = false
            } else if (argument in dangerousOptionsWithValues) {
                skipNext = true
            } else if (dangerousOptionsWithValues.any { argument.startsWith("$it=") } ||
                argument.startsWith("--exec") || argument.startsWith("--external-downloader")
            ) {
                Unit
            } else if (argument in optionsWithValues) {
                skipNext = true
            } else if (argument in flags ||
                optionsWithValues.any { argument.startsWith("$it=") } ||
                argument.contains("impersonate", ignoreCase = true)
            ) {
                Unit
            } else {
                result += argument
            }
        }
        return result
    }

    fun buildVideoFormat(height: Int?, prefer60Fps: Boolean): String {
        val heightFilter = if (height != null && height > 0) "[height<=$height]" else ""
        val tiers = mutableListOf<String>()
        if (prefer60Fps) {
            tiers.add("bestvideo$heightFilter[fps>=60]+bestaudio")
        }
        tiers.add("bestvideo$heightFilter+bestaudio")
        if (heightFilter.isNotEmpty()) {
            tiers.add("best$heightFilter")
        }
        tiers.add("best")
        return tiers.joinToString("/")
    }

    internal const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/138.0 Mobile Safari/537.36"
}
