package com.mvrk.vrka

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val openQueueRequests = MutableStateFlow(0L)
    private val openSettingsRequests = MutableStateFlow(0L)

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        com.mvrk.vrka.mi5g.sync.SyncManager.get(this).onAppOpen()
        enableEdgeToEdge()
        setContent {
            VrkaRoot(
                manager = vrkaApplication.downloads,
                openQueueRequests = openQueueRequests,
                openSettingsRequests = openSettingsRequests,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == ACTION_OPEN_APP_UPDATE || intent.getBooleanExtra(EXTRA_OPEN_APP_UPDATE, false)) {
            vrkaApplication.downloads.appUpdateManager.restoreReadyUpdateFlow()
            intent.removeExtra(EXTRA_OPEN_APP_UPDATE)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            openSettingsRequests.value = System.currentTimeMillis()
            intent.removeExtra(EXTRA_OPEN_SETTINGS)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_QUEUE, false)) {
            openQueueRequests.value = System.currentTimeMillis()
            intent.removeExtra(EXTRA_OPEN_QUEUE)
        }
        intent.getStringExtra(EXTRA_FALLBACK_JOB_ID)?.let { jobId ->
            vrkaApplication.downloads.showFallbackView(jobId)
            openQueueRequests.value = System.currentTimeMillis()
            intent.removeExtra(EXTRA_FALLBACK_JOB_ID)
        }
    }

    companion object {
        const val EXTRA_OPEN_QUEUE = "com.mvrk.vrka.OPEN_QUEUE"
        const val EXTRA_URL = "com.mvrk.vrka.URL"
        const val EXTRA_OPEN_SETTINGS = "com.midroid.mi5gdl.OPEN_SETTINGS"
        const val EXTRA_FALLBACK_JOB_ID = "com.mvrk.vrka.FALLBACK_JOB_ID"
        const val ACTION_OPEN_APP_UPDATE = "com.mvrk.vrka.action.OPEN_APP_UPDATE"
        const val EXTRA_OPEN_APP_UPDATE = "com.mvrk.vrka.OPEN_APP_UPDATE"
    }
}

object UrlExtractor {
    fun extractUrl(text: String?): String? =
        com.mvrk.vrka.mi5g.share.ShareUrlExtractor.extractUrl(text) ?: ShareLinkValidator.extract(text)
}
