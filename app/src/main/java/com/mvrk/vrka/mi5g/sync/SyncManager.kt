package com.mvrk.vrka.mi5g.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mvrk.vrka.AudioFormat
import com.mvrk.vrka.BuildConfig
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.JobState
import com.mvrk.vrka.MediaMode
import com.mvrk.vrka.VideoQuality
import com.mvrk.vrka.VrkaDownloadManager
import com.mvrk.vrka.vrkaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Device-local sync configuration (never synced). */
data class SyncConfig(
    val fileUri: String? = null,
    val fileLabel: String? = null,
    val fileId: String? = null,
    val deviceId: String = "",
    val deviceName: String = "",
    val autoOnOpen: Boolean = false,
    val intervalHours: Int = 0,
    val lastSyncAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val lastOk: Boolean = true,
    val lastMessage: String? = null,
    val otherDevices: List<String> = emptyList(),
)

/**
 * Mi5G Dl settings & history sync through one JSON file picked with SAF (Google Drive etc.).
 * Pull → merge (LWW per setting key / per history entry, tombstones) → apply → push.
 */
class SyncManager private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("mi5g_sync", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<SyncConfig> = _config.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val downloads: VrkaDownloadManager get() = context.vrkaApplication.downloads

    /** Tracks per-key setting change times while the app runs. */
    fun startTracking() {
        scope.launch {
            downloads.settingsRepository.syncableValues.collect { values ->
                mutex.withLock { saveStamps(SettingsStamps.stamp(loadStamps(), values, System.currentTimeMillis())) }
            }
        }
    }

    // ------------------------------------------------------------------ config

    private fun loadConfig(): SyncConfig {
        var deviceId = prefs.getString("deviceId", null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("deviceId", deviceId).apply()
        }
        return SyncConfig(
            fileUri = prefs.getString("fileUri", null),
            fileLabel = prefs.getString("fileLabel", null),
            fileId = prefs.getString("fileId", null),
            deviceId = deviceId,
            deviceName = prefs.getString("deviceName", null) ?: defaultDeviceName(),
            autoOnOpen = prefs.getBoolean("autoOnOpen", false),
            intervalHours = prefs.getInt("intervalHours", 0),
            lastSyncAt = prefs.getLong("lastSyncAt", 0L),
            lastAttemptAt = prefs.getLong("lastAttemptAt", 0L),
            lastOk = prefs.getBoolean("lastOk", true),
            lastMessage = prefs.getString("lastMessage", null),
            otherDevices = prefs.getString("otherDevices", "").orEmpty().split('\n').filter { it.isNotBlank() },
        )
    }

    private fun saveConfig(c: SyncConfig) {
        prefs.edit()
            .putString("fileUri", c.fileUri).putString("fileLabel", c.fileLabel).putString("fileId", c.fileId)
            .putString("deviceName", c.deviceName).putBoolean("autoOnOpen", c.autoOnOpen).putInt("intervalHours", c.intervalHours)
            .putLong("lastSyncAt", c.lastSyncAt).putLong("lastAttemptAt", c.lastAttemptAt).putBoolean("lastOk", c.lastOk)
            .putString("lastMessage", c.lastMessage).putString("otherDevices", c.otherDevices.joinToString("\n"))
            .apply()
        _config.value = c
    }

    private fun defaultDeviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ")
            .replaceFirstChar { it.uppercase() }.ifBlank { "Android" }

    fun setFile(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val label = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        // A newly picked file starts a new pairing: its own fileId is adopted on the next sync.
        saveConfig(_config.value.copy(fileUri = uri.toString(), fileLabel = label ?: uri.lastPathSegment, fileId = null, lastMessage = null))
        schedule()
    }

    fun disconnect() {
        _config.value.fileUri?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        saveConfig(_config.value.copy(fileUri = null, fileLabel = null, fileId = null, lastMessage = null, otherDevices = emptyList()))
        schedule()
    }

    fun setAutoOnOpen(value: Boolean) = saveConfig(_config.value.copy(autoOnOpen = value))

    fun setIntervalHours(hours: Int) {
        saveConfig(_config.value.copy(intervalHours = hours.coerceIn(0, 168)))
        schedule()
    }

    /** (Re)schedules the periodic WorkManager sync. */
    fun schedule() {
        val c = _config.value
        val wm = WorkManager.getInstance(context)
        if (c.fileUri == null || c.intervalHours <= 0) {
            wm.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(c.intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Called when the app opens. */
    fun onAppOpen() {
        val c = _config.value
        if (c.fileUri == null || !c.autoOnOpen) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(OPEN_WORK, ExistingWorkPolicy.KEEP, request)
    }

    // ------------------------------------------------------------------ stamps

    private fun loadStamps(): SettingsStamps.State {
        val values = HashMap<String, Any>(); val stamps = HashMap<String, Long>()
        runCatching {
            val v = JSONObject(prefs.getString("stampValues", "{}")!!)
            v.keys().forEach { k -> SyncCodec.coerce(k, v.opt(k))?.let { values[k] = it } }
            val t = JSONObject(prefs.getString("stampTimes", "{}")!!)
            t.keys().forEach { k -> stamps[k] = t.optLong(k) }
        }
        return SettingsStamps.State(values, stamps)
    }

    private fun saveStamps(s: SettingsStamps.State) {
        prefs.edit()
            .putString("stampValues", JSONObject(s.values as Map<*, *>).toString())
            .putString("stampTimes", JSONObject(s.stamps as Map<*, *>).toString())
            .apply()
    }

    // ------------------------------------------------------------------ sync

    data class Report(val pulledSettings: Int, val pulledHistory: Int, val deleted: Int, val wrote: Boolean, val fileId: String)

    suspend fun syncNow(trigger: String): Result<Report> = withContext(Dispatchers.IO) {
        mutex.withLock {
            _running.value = true
            val c = _config.value
            try {
                val uriText = c.fileUri ?: throw IllegalStateException("No sync file selected")
                val uri = Uri.parse(uriText)
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                val remote = SyncCodec.decode(text)
                if (c.fileId != null && remote.fileId != null && remote.fileId != c.fileId) {
                    throw IllegalStateException("This file belongs to another sync set (file ID ${remote.fileId.take(8)}…, expected ${c.fileId.take(8)}…). Pick the right file again.")
                }
                val now = System.currentTimeMillis()
                val repo = downloads.settingsRepository
                val stamps = SettingsStamps.stamp(loadStamps(), repo.exportSyncable(), now)
                saveStamps(stamps)

                val local = SyncSnapshot(
                    settings = SettingsStamps.snapshot(stamps),
                    history = localHistory(c),
                )
                val merged0 = SyncMerge.merge(local, remote)
                // A local value that embeds credentials (e.g. a custom command with cookies) is never
                // uploaded, and must not be overwritten by another phone's value either.
                val localOnly = stamps.values.filter { (_, v) -> SyncPolicy.valueLooksSecret(v) }.keys
                val result = merged0.copy(settingsToApply = merged0.settingsToApply - localOnly)

                // apply settings; remember their winning stamps so they don't look like local edits
                if (result.settingsToApply.isNotEmpty()) {
                    repo.applySyncable(result.settingsToApply)
                    val applied = SettingsStamps.State(
                        stamps.values + result.settingsToApply,
                        stamps.stamps + result.settingsToApply.keys.associateWith { result.merged.settings[it]?.updatedAt ?: now },
                    )
                    saveStamps(applied)
                }
                // apply history
                val tombTimes = result.merged.history.values.filter { it.deleted }
                    .associate { it.id to (it.deletedAt ?: it.updatedAt) }
                downloads.applySyncedHistory(
                    upserts = result.historyUpserts.map { toJob(it, c.deviceName) },
                    deletes = result.historyDeletes,
                    tombstoneTimes = tombTimes.filterKeys { it in result.historyDeletes || it in result.aliases.keys || it in remote.history },
                )

                val fileId = result.merged.fileId ?: UUID.randomUUID().toString()
                val me = DeviceInfo(c.deviceName, now, BuildConfig.VERSION_NAME)
                val pruned = SyncMerge.pruneTombstones(result.merged, now)
                val out = pruned.copy(fileId = fileId, devices = pruned.devices + (c.deviceId to me), updatedAt = now)
                val write = SyncMerge.needsPush(pruned, remote) || remote.devices[c.deviceId]?.name != c.deviceName ||
                    now - (remote.devices[c.deviceId]?.lastSyncAt ?: 0L) > 24 * 3600 * 1000L
                if (write) {
                    val bytes = SyncCodec.encode(out).toByteArray(Charsets.UTF_8)
                    val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                        ?: context.contentResolver.openOutputStream(uri, "w")
                        ?: throw java.io.IOException("Cannot write the sync file")
                    stream.use { it.write(bytes) }
                }
                val report = Report(result.settingsToApply.size, result.historyUpserts.size, result.historyDeletes.size, write, fileId)
                saveConfig(
                    _config.value.copy(
                        fileId = fileId, lastSyncAt = now, lastAttemptAt = now, lastOk = true,
                        lastMessage = "↓ ${report.pulledSettings} settings, ${report.pulledHistory} history, ${report.deleted} deleted" +
                            if (write) " · ↑ written" else " · no upload needed",
                        otherDevices = out.devices.filterKeys { it != c.deviceId }.values
                            .sortedByDescending { it.lastSyncAt }.map { it.name },
                    ),
                )
                Log.i(TAG, "Sync ($trigger) ok: $report")
                Result.success(report)
            } catch (e: Exception) {
                val msg = when (e) {
                    is SyncFormatException -> when (e.code) {
                        "wrong_format", "not_json" -> "The selected file is not a Mi5G Dl sync file."
                        "newer_version" -> "The sync file was written by a newer Mi5G Dl. Update this app."
                        else -> e.code
                    }
                    is SecurityException -> "No access to the sync file any more. Pick it again."
                    else -> e.message ?: e.javaClass.simpleName
                }
                Log.w(TAG, "Sync ($trigger) failed: $msg")
                saveConfig(_config.value.copy(lastAttemptAt = System.currentTimeMillis(), lastOk = false, lastMessage = msg))
                Result.failure(e)
            } finally {
                _running.value = false
            }
        }
    }

    private fun localHistory(c: SyncConfig): Map<String, HistoryRecord> {
        val live = downloads.historySnapshot().associate { it.id to toRecord(it, c.deviceName) }
        val tombs = downloads.tombstones.all().filterKeys { it !in live }.mapValues { (id, at) -> HistoryRecord.tombstone(id, at) }
        return live + tombs
    }

    companion object {
        private const val TAG = "Mi5G-Sync"
        const val PERIODIC_WORK = "MI5G_SYNC_PERIODIC"
        const val OPEN_WORK = "MI5G_SYNC_ON_OPEN"

        @Volatile private var instance: SyncManager? = null
        fun get(context: Context): SyncManager = instance ?: synchronized(this) {
            instance ?: SyncManager(context.applicationContext).also { instance = it }
        }

        fun toRecord(job: DownloadJob, deviceName: String): HistoryRecord = HistoryRecord(
            id = job.id,
            updatedAt = job.updatedAt,
            url = job.request.url,
            title = job.title,
            state = job.state.name,
            mode = job.request.mode.name,
            quality = job.request.quality.name,
            audioFormat = job.request.audioFormat.name,
            bitrate = job.request.mp3Bitrate,
            trimStart = job.request.trimStart,
            trimEnd = job.request.trimEnd,
            trimPrecise = job.request.trimPrecise,
            detail = job.detail,
            error = job.error,
            actualResolution = job.actualResolution,
            createdAt = job.createdAt,
            device = job.syncedFrom.ifBlank { deviceName },
        )

        fun toJob(r: HistoryRecord, myDeviceName: String): DownloadJob {
            val state = JobState.entries.firstOrNull { it.name == r.state }?.takeIf { it.isTerminal } ?: JobState.FAILED
            return DownloadJob(
                id = r.id,
                request = DownloadRequest(
                    url = r.url,
                    mode = MediaMode.entries.firstOrNull { it.name == r.mode } ?: MediaMode.VIDEO,
                    quality = VideoQuality.entries.firstOrNull { it.name == r.quality } ?: VideoQuality.BEST,
                    audioFormat = AudioFormat.entries.firstOrNull { it.name == r.audioFormat } ?: AudioFormat.MP3,
                    mp3Bitrate = r.bitrate,
                    trimStart = r.trimStart,
                    trimEnd = r.trimEnd,
                    trimPrecise = r.trimPrecise,
                ),
                state = state,
                title = r.title,
                detail = r.detail,
                progress = if (state == JobState.DONE) 1f else 0f,
                createdAt = r.createdAt,
                updatedAt = r.updatedAt,
                error = r.error,
                actualResolution = r.actualResolution,
                syncedFrom = if (r.device == myDeviceName) "" else r.device,
            )
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = SyncManager.get(applicationContext)
        if (manager.config.value.fileUri == null) return Result.success()
        return if (manager.syncNow("background").isSuccess) Result.success()
        else if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
