package com.mvrk.vrka

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class VrkaApplication : Application() {
    val downloads: VrkaDownloadManager by lazy {
        VrkaDownloadManager.create(this)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Mi5G Dl 1.1.0: settings-change tracking for Drive-file sync, periodic sync and the optional
        // daily self-update check. Failures here must never prevent the app from starting.
        runCatching {
            val sync = com.mvrk.vrka.mi5g.sync.SyncManager.get(this)
            sync.startTracking()
            sync.schedule()
        }.onFailure { Log.w("Mi5G", "sync init failed: ${it.javaClass.simpleName}") }
        // Keep the daily update-check worker in line with the setting (which may also change via sync).
        appScope.launch {
            runCatching {
                downloads.settingsRepository.settings
                    .map { it.updateDailyCheck }
                    .distinctUntilChanged()
                    .collect { com.mvrk.vrka.mi5g.update.Mi5gUpdateManager.get(this@VrkaApplication).setDailyCheck(it) }
            }.onFailure { Log.w("Mi5G", "update check scheduling failed: ${it.javaClass.simpleName}") }
        }
    }
}

val Context.vrkaApplication: VrkaApplication
    get() = applicationContext as VrkaApplication
