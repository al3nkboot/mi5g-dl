package com.mvrk.vrka.mi5g.playlist

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mvrk.vrka.AppSettings
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.MediaMode
import com.mvrk.vrka.R
import com.mvrk.vrka.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.UUID

/** Full-screen playlist picker → batch progress → failures/retry. Items go through the normal download queue. */
@Composable
fun PlaylistDialog(
    url: String,
    settings: AppSettings,
    jobs: List<DownloadJob>,
    onProbe: suspend (String) -> PlaylistRules.Playlist,
    onEnqueue: (DownloadRequest) -> Unit,
    onRetry: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var playlist by remember(url) { mutableStateOf<PlaylistRules.Playlist?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var group by remember(url) { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        runCatching { onProbe(url) }.onSuccess { playlist = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val p = playlist
            val g = group
            when {
                error != null -> Message(stringResource(R.string.pl_load_failed, error!!), onDismiss)
                p == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(); Text(stringResource(R.string.pl_loading), Modifier.padding(16.dp))
                    }
                }
                g == null -> PlaylistPicker(p, settings, onDismiss) { selected, mode, quality ->
                    val id = UUID.randomUUID().toString()
                    val folder = PlaylistRules.folderName(p.title)
                    selected.forEach { e ->
                        onEnqueue(
                            DownloadRequest(
                                url = e.url, mode = mode, quality = quality,
                                audioFormat = settings.defaultAudioFormat, mp3Bitrate = settings.defaultMp3Bitrate,
                                playlistGroup = id, playlistFolder = folder,
                            ),
                        )
                    }
                    group = id
                }
                else -> PlaylistProgressView(p.title, PlaylistRules.progress(jobs, g), onRetry, onDismiss)
            }
        }
    }
}

@Composable
private fun Message(text: String, onDismiss: () -> Unit) = Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
    Text(text, color = MaterialTheme.colorScheme.error)
    TextButton(onDismiss) { Text(stringResource(R.string.pl_close)) }
}

@Composable
fun PlaylistPicker(
    playlist: PlaylistRules.Playlist,
    settings: AppSettings,
    onDismiss: () -> Unit,
    loadThumbnails: Boolean = true,
    onDownload: (List<PlaylistRules.Entry>, MediaMode, VideoQuality) -> Unit,
) {
    var checked by remember(playlist) { mutableStateOf(playlist.entries.indices.toSet()) }
    var mode by remember { mutableStateOf(settings.defaultMode) }
    var quality by remember { mutableStateOf(settings.defaultQuality) }
    val all = checked.size == playlist.entries.size
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(playlist.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().clickable { checked = if (all) emptySet() else playlist.entries.indices.toSet() }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(all, onCheckedChange = { checked = if (it) playlist.entries.indices.toSet() else emptySet() })
            Text(stringResource(R.string.pl_select_all), Modifier.weight(1f))
            Text(stringResource(R.string.pl_selected, checked.size, playlist.entries.size), style = MaterialTheme.typography.bodySmall)
        }
        // One format/quality applies to every selected item.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == MediaMode.VIDEO, { mode = MediaMode.VIDEO }, { Text(stringResource(R.string.pl_video)) })
            FilterChip(mode == MediaMode.AUDIO, { mode = MediaMode.AUDIO }, { Text(stringResource(R.string.pl_audio)) })
        }
        if (mode == MediaMode.VIDEO) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(VideoQuality.BEST, VideoQuality.P1080, VideoQuality.P720, VideoQuality.P480).forEach { q ->
                    FilterChip(quality == q, { quality = q }, { Text(if (q == VideoQuality.BEST) "Best" else q.label) })
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(playlist.entries) { i, e ->
                Row(
                    Modifier.fillMaxWidth().clickable { checked = if (i in checked) checked - i else checked + i }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(i in checked, onCheckedChange = { checked = if (it) checked + i else checked - i })
                    Thumbnail(e.thumbnail.takeIf { loadThumbnails })
                    Text("${i + 1}. ${e.title}", Modifier.padding(start = 10.dp).weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onDismiss, Modifier.weight(1f)) { Text(stringResource(R.string.pl_close)) }
            Button(
                onClick = { onDownload(checked.sorted().map(playlist.entries::get), mode, quality) },
                enabled = checked.isNotEmpty(), modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.pl_download, checked.size)) }
        }
    }
}

@Composable
fun PlaylistProgressView(title: String, progress: PlaylistRules.Progress, onRetry: (String) -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.pl_saving_to, PlaylistRules.folderName(title)), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.pl_overall, progress.finished, progress.total), style = MaterialTheme.typography.headlineMedium)
        LinearProgressIndicator({ if (progress.total == 0) 0f else progress.finished / progress.total.toFloat() }, Modifier.fillMaxWidth())
        progress.current?.let { job ->
            Text(stringResource(R.string.pl_now, job.title.ifBlank { job.request.url }), maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator({ job.progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            Text("${job.state.label} ${job.speed}", style = MaterialTheme.typography.bodySmall)
        }
        if (progress.isComplete && progress.failed.isEmpty()) Text(stringResource(R.string.pl_done), color = MaterialTheme.colorScheme.primary)
        if (progress.failed.isNotEmpty()) {
            Text(stringResource(R.string.pl_failed, progress.failed.size), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                itemsIndexed(progress.failed) { _, job ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(job.title.ifBlank { job.request.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(job.error, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton({ onRetry(job.id) }) { Text(stringResource(R.string.pl_retry)) }
                    }
                }
            }
            if (progress.isComplete) Button({ progress.failed.forEach { onRetry(it.id) } }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.pl_retry_failed)) }
        }
        OutlinedButton(onDismiss, Modifier.fillMaxWidth()) { Text(stringResource(R.string.pl_close)) }
    }
}

private val thumbCache = LruCache<String, Bitmap>(64)

@Composable
private fun Thumbnail(url: String?) {
    val bmp by produceState(url?.let(thumbCache::get), url) {
        if (url != null && value == null) value = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
            }.getOrNull()?.also { thumbCache.put(url, it) }
        }
    }
    val m = Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
    bmp?.let { Image(it.asImageBitmap(), null, m, contentScale = ContentScale.Crop) } ?: Box(m)
}
