package com.mvrk.vrka.mi5g.youtube

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.mvrk.vrka.GeckoRuntimeManager
import com.mvrk.vrka.VrkaOutlinedButton
import com.mvrk.vrka.VrkaTextButton
import com.mvrk.vrka.VrkaTheme
import com.mvrk.vrka.VrkaTokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

/**
 * Mi5G Dl: optional YouTube sign-in in a private GeckoView session. After "Done" the session's
 * youtube.com/google.com cookies are exported to an app-private cookies.txt and the private session
 * is closed (as the yt-dlp wiki recommends, so the exported session is not rotated by the browser).
 * Google may refuse sign-in in embedded browsers; the screen explains that and nothing breaks.
 */
class YouTubeSignInActivity : ComponentActivity() {
    private var session: GeckoSession? = null
    private var canGoBack = false
    private var pageStop: CompletableDeferred<Boolean>? = null

    private var status by mutableStateOf("Loading Google sign-in…")
    private var isError by mutableStateOf(false)
    private var loadProgress by mutableStateOf(0)
    private var busy by mutableStateOf(false)
    private var host by mutableStateOf("accounts.google.com")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = YouTubeAccountManager.get(this)
        val runtime = GeckoRuntimeManager.getInstance(this).runtime
        val s = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).useTrackingProtection(false).build())
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { this@YouTubeSignInActivity.canGoBack = canGoBack }
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                host = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
                if (!busy && (host.endsWith("youtube.com"))) {
                    status = "If you can see your YouTube avatar, tap Done."
                    isError = false
                }
            }
            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): org.mozilla.geckoview.GeckoResult<String>? {
                status = "The page could not be loaded (error ${error.code}). Check the connection and try again."
                isError = true
                return null
            }
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, p: Int) { loadProgress = p }
            override fun onPageStop(session: GeckoSession, success: Boolean) { pageStop?.complete(success) }
        }
        s.open(runtime)
        session = s
        lifecycleScope.launch {
            manager.ensureHelper()
            s.loadUri(YouTubeAccountManager.SIGN_IN_URL)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canGoBack) session?.goBack() else finish()
            }
        })

        setContent {
            VrkaTheme {
                Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("YouTube sign-in", color = VrkaTokens.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                VrkaTextButton(text = "Cancel", onClick = { finish() })
                                VrkaOutlinedButton(text = if (busy) "Saving…" else "Done", enabled = !busy, onClick = { finishSignIn(manager) }, height = 32.dp, borderColor = VrkaTokens.Accent)
                            }
                        }
                        Text(
                            "Optional. Sign in with your Google account, then tap Done. Only youtube.com/google.com cookies are saved, on this phone only, and used only for YouTube links. " +
                                "Google sometimes blocks sign-in inside apps (\"This browser or app may not be secure\") — if so, just cancel: downloads keep working without it.",
                            color = VrkaTokens.TextSecondary, fontSize = 11.sp, lineHeight = 14.sp,
                        )
                        Text(status, color = if (isError) VrkaTokens.Error else VrkaTokens.AccentLight, fontSize = 12.sp)
                        if (loadProgress in 1..99) LinearProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth(), color = VrkaTokens.Accent)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(factory = { ctx -> GeckoView(ctx).apply { setSession(s) } }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    private fun finishSignIn(manager: YouTubeAccountManager) {
        val s = session ?: return
        busy = true
        isError = false
        status = "Saving your YouTube session…"
        lifecycleScope.launch {
            // yt-dlp wiki: open youtube.com/robots.txt in the signed-in private window before exporting.
            pageStop = CompletableDeferred()
            s.loadUri(YouTubeAccountManager.ROBOTS_URL)
            withTimeoutOrNull(8_000) { pageStop?.await() }
            val result = manager.exportFromBrowser()
            busy = false
            result.onSuccess {
                status = it
                setResult(RESULT_OK)
                finish()
            }.onFailure {
                status = it.message ?: "Sign-in could not be saved."
                isError = true
                s.loadUri("https://m.youtube.com/")
            }
        }
    }

    override fun onDestroy() {
        // Closing the private session discards the browser's copy of the session.
        session?.close()
        session = null
        YouTubeAccountManager.get(this).refresh()
        super.onDestroy()
    }
}
