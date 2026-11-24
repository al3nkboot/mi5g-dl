package com.mvrk.vrka

import android.app.Application
import android.content.Context
import android.util.Log
import com.mvrk.vrka.storage.StorageRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class VrkaApplication : Application() {
    private lateinit var applicationScope: CoroutineScope
    internal lateinit var storageRuntime: StorageRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        storageRuntime = StorageRuntime(this, applicationScope).also { it.start() }
        // Mi5G Dl: Drive-file sync and the optional daily self-update check.
        // Failures here must never prevent the app from starting.
        runCatching {
            val sync = com.mvrk.vrka.mi5g.sync.SyncManager.get(this)
            sync.startTracking()
            sync.schedule()
        }.onFailure { Log.w("Mi5G", "sync init failed: ${it.javaClass.simpleName}") }
        applicationScope.launch {
            runCatching {
                downloads.settingsRepository.settings
                    .map { it.updateDailyCheck }
                    .distinctUntilChanged()
                    .collect { com.mvrk.vrka.mi5g.update.Mi5gUpdateManager.get(this@VrkaApplication).setDailyCheck(it) }
            }.onFailure { Log.w("Mi5G", "update check scheduling failed: ${it.javaClass.simpleName}") }
        }
    }

    val downloads: VrkaDownloadManager by lazy {
        VrkaDownloadManager(
            context = this,
            settingsRepository = SettingsRepository(this, applicationScope),
            scope = applicationScope,
            customRepository = storageRuntime.repository,
            storageRuntime = storageRuntime,
        )
    }
}

val Context.vrkaApplication: VrkaApplication
    get() = applicationContext as VrkaApplication
