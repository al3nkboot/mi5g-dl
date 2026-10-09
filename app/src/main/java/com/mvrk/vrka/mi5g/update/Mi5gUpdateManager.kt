package com.mvrk.vrka.mi5g.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mvrk.vrka.MainActivity
import com.mvrk.vrka.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface UpdateDownload {
    data object Idle : UpdateDownload
    data class Running(val downloaded: Long, val total: Long) : UpdateDownload
    data class Ready(val file: File) : UpdateDownload
    data class Failed(val message: String) : UpdateDownload
}

data class UpdateUiState(
    val hasToken: Boolean = false,
    val tokenHint: String? = null,
    val checking: Boolean = false,
    val lastCheckAt: Long = 0L,
    val installedVersionName: String = "",
    val installedVersionCode: Long = 0L,
    val release: UpdateRules.Release? = null,
    val remoteVersionCode: Long? = null,
    val updateAvailable: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val download: UpdateDownload = UpdateDownload.Idle,
)

/**
 * Mi5G Dl self-updater. Checks ONLY https://api.github.com/repos/al3nkboot/mi5g-dl/releases/latest
 * (private repo → needs the user's fine-grained token), downloads the arm64 APK asset through the
 * API, verifies size/SHA-256/package/versionCode/signing certificate and then hands it to the
 * system installer.
 */
class Mi5gUpdateManager private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("mi5g_update", Context.MODE_PRIVATE)
    val tokens = TokenStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    init {
        observeDownloadWork()
    }

    private fun installedInfo(): PackageInfo = packageInfo(context.packageManager, context.packageName)

    private fun initialState(): UpdateUiState {
        val info = runCatching { installedInfo() }.getOrNull()
        val installedCode = info?.let(::versionCodeOf) ?: 0L
        val release = prefs.getString("releaseJson", null)?.let(UpdateRules::parseRelease)
        val remoteCode = prefs.getLong("remoteVersionCode", -1L).takeIf { it > 0 }
        val available = release != null && UpdateRules.isNewer(remoteCode, release.version, installedCode, info?.versionName)
        return UpdateUiState(
            hasToken = tokens.hasToken(),
            tokenHint = tokens.hint(),
            lastCheckAt = prefs.getLong("lastCheckAt", 0L),
            installedVersionName = info?.versionName.orEmpty(),
            installedVersionCode = installedCode,
            release = release.takeIf { available },
            remoteVersionCode = remoteCode.takeIf { available },
            updateAvailable = available,
        )
    }

    fun saveToken(token: String) {
        tokens.set(token)
        _state.value = _state.value.copy(hasToken = true, tokenHint = tokens.hint(), message = "Token saved (encrypted).", isError = false)
    }

    fun clearToken() {
        tokens.clear()
        _state.value = _state.value.copy(hasToken = false, tokenHint = null, message = "Token removed.", isError = false)
    }

    fun checkNow() {
        if (_state.value.checking) return
        _state.value = _state.value.copy(checking = true, message = null, isError = false)
        scope.launch {
            val result = runCheck()
            _state.value = result.copy(checking = false)
        }
    }

    /** Performs a check and returns the new state (used by the UI and the daily worker). */
    suspend fun runCheck(): UpdateUiState = withContext(Dispatchers.IO) {
        val base = _state.value
        val token = tokens.get()
        if (token.isNullOrBlank()) {
            return@withContext base.copy(
                hasToken = false,
                message = "The update repository ($REPO_LABEL) is private, so GitHub needs a read-only token. Add one below.",
                isError = true,
            )
        }
        try {
            val client = GitHubClient(token)
            val json = client.getText(UpdateRules.LATEST_RELEASE_URL)
            val release = UpdateRules.parseRelease(json)
                ?: return@withContext base.copy(message = "Latest release has no Mi5G-Dl-<version>-arm64.apk asset.", isError = true)
            val manifest = release.updateJson?.let { asset ->
                runCatching { UpdateRules.parseManifest(client.getText(asset.apiUrl, "application/octet-stream")) }.getOrNull()
            }
            val info = installedInfo()
            val installedCode = versionCodeOf(info)
            val available = UpdateRules.isNewer(manifest?.versionCode, release.version, installedCode, info.versionName)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putLong("lastCheckAt", now)
                .putString("releaseJson", json)
                .putLong("remoteVersionCode", manifest?.versionCode ?: -1L)
                .putString("manifestSha", manifest?.sha256)
                .apply()
            base.copy(
                hasToken = true,
                lastCheckAt = now,
                installedVersionCode = installedCode,
                installedVersionName = info.versionName.orEmpty(),
                release = release,
                remoteVersionCode = manifest?.versionCode,
                updateAvailable = available,
                message = if (available) "Mi5G Dl ${release.version} is available." else "You're up to date (${release.version} is the latest).",
                isError = false,
            )
        } catch (e: GitHubClient.HttpException) {
            base.copy(message = e.message, isError = true)
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            base.copy(message = "Update check failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    fun startDownload() {
        val s = _state.value
        val release = s.release ?: return
        if (!s.updateAvailable) return
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    UpdateDownloadWorker.KEY_URL to release.apk.apiUrl,
                    UpdateDownloadWorker.KEY_NAME to release.apk.name,
                    UpdateDownloadWorker.KEY_SIZE to release.apk.size,
                    UpdateDownloadWorker.KEY_SHA to (release.apk.sha256 ?: prefs.getString("manifestSha", null)),
                    UpdateDownloadWorker.KEY_CODE to (s.remoteVersionCode ?: -1L),
                ),
            )
            .build()
        _state.value = s.copy(download = UpdateDownload.Running(0, release.apk.size))
        WorkManager.getInstance(context).enqueueUniqueWork(DOWNLOAD_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelDownload() {
        WorkManager.getInstance(context).cancelUniqueWork(DOWNLOAD_WORK)
        _state.value = _state.value.copy(download = UpdateDownload.Idle)
    }

    private fun observeDownloadWork() {
        scope.launch {
            runCatching {
                WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(DOWNLOAD_WORK).collect { infos ->
                    val info = infos.firstOrNull() ?: return@collect
                    val d: UpdateDownload = when (info.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> UpdateDownload.Running(
                            info.progress.getLong(UpdateDownloadWorker.KEY_DONE, 0L),
                            info.progress.getLong(UpdateDownloadWorker.KEY_TOTAL, _state.value.release?.apk?.size ?: 0L),
                        )
                        WorkInfo.State.SUCCEEDED -> info.outputData.getString(UpdateDownloadWorker.KEY_FILE)
                            ?.let { File(it) }?.takeIf { it.isFile }?.let { UpdateDownload.Ready(it) } ?: UpdateDownload.Idle
                        WorkInfo.State.FAILED -> UpdateDownload.Failed(info.outputData.getString(UpdateDownloadWorker.KEY_ERROR) ?: "Download failed")
                        WorkInfo.State.CANCELLED -> UpdateDownload.Idle
                    }
                    _state.value = _state.value.copy(download = d)
                }
            }
        }
    }

    /** Opens the system installer (asks for "install unknown apps" permission first if needed). */
    fun install(file: File): String? {
        val error = verify(context, file, _state.value.release?.apk?.size ?: 0L,
            _state.value.release?.apk?.sha256 ?: prefs.getString("manifestSha", null), _state.value.remoteVersionCode)
        if (error != null) {
            _state.value = _state.value.copy(download = UpdateDownload.Failed(error))
            return error
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "Allow \"Install unknown apps\" for Mi5G Dl, then tap Install again."
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return null
    }

    fun setDailyCheck(enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(CHECK_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        wm.enqueueUniquePeriodicWork(CHECK_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal fun publishBackgroundResult(result: UpdateUiState) {
        _state.value = result.copy(checking = false, download = _state.value.download)
        val release = result.release ?: return
        if (!result.updateAvailable) return
        val key = "${release.version}/${result.remoteVersionCode}"
        if (prefs.getString("notifiedFor", null) == key) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 51,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Mi5G Dl ${release.version} is available")
            .setContentText("Tap to view release notes and update (${UpdateRules.formatSize(release.apk.size)}).")
            .setStyle(NotificationCompat.BigTextStyle().bigText(release.notes.take(600)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFY_AVAILABLE, n)
        prefs.edit().putString("notifiedFor", key).apply()
    }

    companion object {
        private const val TAG = "Mi5G-Updater"
        const val REPO_LABEL = UpdateRules.REPO
        const val DOWNLOAD_WORK = "MI5G_UPDATE_DOWNLOAD"
        const val CHECK_WORK = "MI5G_UPDATE_DAILY_CHECK"
        const val CHANNEL = "mi5g_app_updates"
        const val NOTIFY_AVAILABLE = 5101
        const val NOTIFY_PROGRESS = 5102

        @Volatile private var instance: Mi5gUpdateManager? = null
        fun get(context: Context): Mi5gUpdateManager = instance ?: synchronized(this) {
            instance ?: Mi5gUpdateManager(context.applicationContext).also { instance = it }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "New Mi5G Dl versions and update download progress"
                    },
                )
            }
        }

        fun versionCodeOf(info: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

        fun packageInfo(pm: PackageManager, name: String): PackageInfo =
            if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
            else @Suppress("DEPRECATION") pm.getPackageInfo(name, PackageManager.GET_SIGNATURES)

        fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
            if (Build.VERSION.SDK_INT >= 28) pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
            else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)

        fun signerDigests(info: PackageInfo?): Set<String> {
            info ?: return emptySet()
            val sigs = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners
            } else @Suppress("DEPRECATION") info.signatures
            return sigs.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun sha256File(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Verifies a downloaded APK against the installed app (package, versionCode, signer, checksum). */
        fun verify(context: Context, file: File, expectedSize: Long, expectedSha: String?, expectedCode: Long?): String? {
            if (!file.isFile) return "Downloaded file is missing."
            val pm = context.packageManager
            val installed = packageInfo(pm, context.packageName)
            val candidate = archiveInfo(pm, file.absolutePath)
            val facts = UpdateRules.ApkFacts(
                packageName = candidate?.packageName,
                versionCode = candidate?.let(::versionCodeOf) ?: -1L,
                versionName = candidate?.versionName,
                signerSha256 = signerDigests(candidate),
                sizeBytes = file.length(),
                sha256 = if (UpdateRules.normalizeSha256(expectedSha) != null) sha256File(file) else null,
            )
            val mine = UpdateRules.ApkFacts(
                packageName = installed.packageName,
                versionCode = versionCodeOf(installed),
                versionName = installed.versionName,
                signerSha256 = signerDigests(installed),
                sizeBytes = 0, sha256 = null,
            )
            return UpdateRules.verifyApk(facts, mine, expectedSize, expectedSha, expectedCode)
        }
    }
}

class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return fail("No asset URL")
        val name = inputData.getString(KEY_NAME) ?: "update.apk"
        val size = inputData.getLong(KEY_SIZE, 0L)
        val sha = inputData.getString(KEY_SHA)
        val code = inputData.getLong(KEY_CODE, -1L).takeIf { it > 0 }
        if (!UpdateRules.isAllowedAssetUrl(url)) return fail("Asset URL is not from ${UpdateRules.REPO}")
        val token = TokenStore(applicationContext).get() ?: return fail("No GitHub token saved")
        val dir = File(applicationContext.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        Mi5gUpdateManager.ensureChannel(applicationContext)
        runCatching { setForeground(foregroundInfo(0, size)) }
        return try {
            GitHubClient(token).download(url, target, onProgress = { done, total ->
                val t = if (total > 0) total else size
                setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to t))
                runCatching { setForegroundAsync(foregroundInfo(done, t)) }
            }, isCancelled = { isStopped })
            val error = Mi5gUpdateManager.verify(applicationContext, target, size, sha, code)
            if (error != null) {
                target.delete()
                fail(error)
            } else Result.success(workDataOf(KEY_FILE to target.absolutePath))
        } catch (e: Exception) {
            target.delete()
            fail(e.message ?: "Download failed")
        }
    }

    private fun fail(message: String) = Result.failure(workDataOf(KEY_ERROR to message))

    private fun foregroundInfo(done: Long, total: Long): ForegroundInfo {
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val n = NotificationCompat.Builder(applicationContext, Mi5gUpdateManager.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Downloading Mi5G Dl update")
            .setContentText("${UpdateRules.formatSize(done)} / ${UpdateRules.formatSize(total)}")
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Mi5gUpdateManager.NOTIFY_PROGRESS, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(Mi5gUpdateManager.NOTIFY_PROGRESS, n)
    }

    companion object {
        const val KEY_URL = "url"; const val KEY_NAME = "name"; const val KEY_SIZE = "size"; const val KEY_SHA = "sha"
        const val KEY_CODE = "code"; const val KEY_DONE = "done"; const val KEY_TOTAL = "total"
        const val KEY_FILE = "file"; const val KEY_ERROR = "error"
    }
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = Mi5gUpdateManager.get(applicationContext)
        if (!manager.tokens.hasToken()) return Result.success()
        val result = manager.runCheck()
        manager.publishBackgroundResult(result)
        return Result.success()
    }
}
