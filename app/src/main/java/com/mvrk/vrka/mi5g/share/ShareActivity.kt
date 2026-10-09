package com.mvrk.vrka.mi5g.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.mvrk.vrka.AppSettings
import com.mvrk.vrka.AudioFormat
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.MainActivity
import com.mvrk.vrka.MediaMode
import com.mvrk.vrka.OutputPublisher
import com.mvrk.vrka.R
import com.mvrk.vrka.SaveLocationMode
import com.mvrk.vrka.VrkaChip
import com.mvrk.vrka.VrkaMonoFamily
import com.mvrk.vrka.VrkaOutlinedButton
import com.mvrk.vrka.VrkaPrimaryButton
import com.mvrk.vrka.VrkaTheme
import com.mvrk.vrka.VrkaTokens
import com.mvrk.vrka.mi5g.trim.TrimSpec
import com.mvrk.vrka.mi5g.ui.TrimEditor
import com.mvrk.vrka.vrkaApplication
import kotlinx.coroutines.launch

/**
 * Mi5G Dl share-to-download: receives links shared from X, YouTube, TikTok, Instagram, browsers…
 * and shows a compact bottom sheet (or downloads instantly when "Skip sheet" is enabled).
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = extract(intent)
        if (url == null) {
            Toast.makeText(this, "Mi5G Dl: no link found in the shared text", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val manager = vrkaApplication.downloads
        lifecycleScope.launch {
            val settings = manager.settingsRepository.current()
            if (settings.shareSkipSheet) {
                enqueue(buildRequest(url, settings, settings.defaultMode, "", "", settings.trimPreciseDefault), settings)
                return@launch
            }
            setContent {
                VrkaTheme(themeMode = settings.themeMode, amoled = settings.amoled, fontPreference = settings.fontPreference) {
                    ShareSheet(
                        url = url,
                        settings = settings,
                        onProbe = { manager.probeMedia(it) },
                        onDownload = { request -> enqueue(request, settings) },
                        onAdvanced = {
                            startActivity(
                                Intent(this@ShareActivity, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_URL, url)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            )
                            finish()
                        },
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

    private fun enqueue(request: DownloadRequest, settings: AppSettings) {
        val tree = settings.outputTreeUri.takeIf {
            settings.isDownloadLocationConfigured && settings.saveLocationMode == SaveLocationMode.REMEMBER_LOCATION &&
                OutputPublisher.isTreePermissionValid(this, it)
        }
        runCatching { vrkaApplication.downloads.enqueue(request.copy(destinationTreeUri = tree)) }
            .onSuccess {
                // Start the foreground download service while this activity is still visible
                // (Android forbids starting it from the background once we return to the source app).
                runCatching { com.mvrk.vrka.DownloadService.start(this) }
                Toast.makeText(this, "Mi5G Dl: download queued", Toast.LENGTH_SHORT).show()
            }
            .onFailure { Toast.makeText(this, "Mi5G Dl: ${it.message}", Toast.LENGTH_LONG).show() }
        // Return to the app the link was shared from.
        finish()
    }

    companion object {
        fun extract(intent: Intent?): String? {
            intent ?: return null
            val candidates = buildList {
                if (intent.action == Intent.ACTION_SEND) {
                    add(intent.getStringExtra(Intent.EXTRA_TEXT))
                    add(intent.getStringExtra(Intent.EXTRA_SUBJECT))
                    intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) add(clip.getItemAt(i).text?.toString()) }
                }
                add(intent.dataString)
            }
            return candidates.firstNotNullOfOrNull { ShareUrlExtractor.extractUrl(it) }
        }

        fun buildRequest(url: String, settings: AppSettings, mode: MediaMode, start: String, end: String, precise: Boolean) =
            DownloadRequest(
                url = url,
                mode = mode,
                quality = settings.defaultQuality,
                audioFormat = settings.defaultAudioFormat,
                mp3Bitrate = settings.defaultMp3Bitrate,
                trimStart = if (TrimSpec.isTrimmed(start, end)) start.trim() else "",
                trimEnd = if (TrimSpec.isTrimmed(start, end)) end.trim() else "",
                trimPrecise = precise && TrimSpec.isTrimmed(start, end),
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(
    url: String,
    settings: AppSettings,
    onProbe: suspend (String) -> com.mvrk.vrka.VrkaDownloadManager.MediaProbe?,
    onDownload: (DownloadRequest) -> Unit,
    onAdvanced: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VrkaTokens.SurfaceCard,
    ) {
        ShareSheetContent(url = url, settings = settings, onProbe = onProbe, onDownload = onDownload, onAdvanced = onAdvanced)
    }
}

/** Stateless-ish sheet body (also rendered by the screenshot test). */
@Composable
fun ShareSheetContent(
    url: String,
    settings: AppSettings,
    onProbe: (suspend (String) -> com.mvrk.vrka.VrkaDownloadManager.MediaProbe?)?,
    onDownload: (DownloadRequest) -> Unit,
    onAdvanced: () -> Unit,
    initialTrimOpen: Boolean = false,
    initialDuration: Double? = null,
    initialTitle: String? = null,
) {
    var mode by remember { mutableStateOf(settings.defaultMode) }
    var trimOpen by remember { mutableStateOf(initialTrimOpen) }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var precise by remember { mutableStateOf(settings.trimPreciseDefault) }
    var duration by remember { mutableStateOf(initialDuration) }
    var title by remember { mutableStateOf(initialTitle) }
    var probing by remember { mutableStateOf(false) }
    var probeMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val trimValidation = TrimSpec.validate(start, end, duration)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
            .navigationBarsPadding(),
    ) {
        Text("Mi5G Dl", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp), color = VrkaTokens.Accent)
        Spacer(Modifier.height(6.dp))
        if (!title.isNullOrBlank()) {
            Text(title!!, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = VrkaTokens.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            url,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily, fontSize = 12.sp),
            color = VrkaTokens.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .background(VrkaTokens.SurfaceInset, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VrkaChip(
                selected = mode == MediaMode.VIDEO,
                onClick = { mode = MediaMode.VIDEO },
                label = "Video · ${settings.defaultQuality.label}",
            )
            VrkaChip(
                selected = mode == MediaMode.AUDIO,
                onClick = { mode = MediaMode.AUDIO },
                label = "Audio · " + when (settings.defaultAudioFormat) {
                    AudioFormat.MP3 -> "MP3 ${settings.defaultMp3Bitrate}k"
                    AudioFormat.OPUS -> "Opus"
                    AudioFormat.WAV -> "WAV"
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            VrkaOutlinedButton(
                text = if (trimOpen) "✂ Trim ▲" else "✂ Trim ▼",
                onClick = { trimOpen = !trimOpen },
                height = 34.dp,
                borderColor = if (trimValidation is TrimSpec.Validation.Ok) VrkaTokens.Accent else VrkaTokens.BorderSubtle,
            )
            VrkaOutlinedButton(text = "Advanced…", onClick = onAdvanced, height = 34.dp)
        }
        if (trimOpen) {
            Spacer(Modifier.height(8.dp))
            TrimEditor(
                start = start, end = end, precise = precise, durationSeconds = duration,
                probing = probing, probeMessage = probeMessage,
                onStartChange = { start = it }, onEndChange = { end = it }, onPreciseChange = { precise = it },
                onProbe = onProbe?.let { probe ->
                    {
                        probing = true
                        probeMessage = "Fetching metadata…"
                        scope.launch {
                            val r = probe(url)
                            probing = false
                            duration = r?.durationSeconds
                            if (!r?.title.isNullOrBlank()) title = r?.title
                            probeMessage = when {
                                r == null -> "Couldn't read metadata; type the times manually."
                                r.durationSeconds == null -> "Length unknown; type the times manually."
                                else -> null
                            }
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(16.dp))
        VrkaPrimaryButton(
            text = "Download",
            iconRes = R.drawable.ic_download,
            enabled = trimValidation !is TrimSpec.Validation.Invalid,
            onClick = { onDownload(ShareActivity.buildRequest(url, settings, mode, start, end, precise)) },
        )
        Text(
            "Saved to Download/Mi5G Dl (or your chosen folder). You can skip this sheet in Settings.",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = VrkaTokens.TextTertiary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
