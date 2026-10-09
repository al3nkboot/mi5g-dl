package com.mvrk.vrka.mi5g.youtube

import android.content.Context
import android.util.Log
import com.mvrk.vrka.GeckoRuntimeManager
import com.mvrk.vrka.awaitResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.atomic.AtomicInteger

data class YouTubeAccountState(
    val signedIn: Boolean = false,
    val cookieCount: Int = 0,
    val savedAt: Long = 0L,
    val message: String? = null,
    val isError: Boolean = false,
)

/**
 * Mi5G Dl optional YouTube sign-in. The user signs in inside a private GeckoView session; the
 * bundled helper extension reads only youtube.com/google.com cookies, which are written to an
 * app-private cookies.txt (see [YouTubeCookieStore]). Cookie values are never logged or synced.
 */
class YouTubeAccountManager private constructor(private val context: Context) :
    WebExtension.MessageDelegate, WebExtension.PortDelegate {

    val store = YouTubeCookieStore(context)
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<YouTubeAccountState> = _state.asStateFlow()

    @Volatile private var port: WebExtension.Port? = null
    @Volatile private var extension: WebExtension? = null
    private val nextId = AtomicInteger(1)
    private val pending = HashMap<Int, CompletableDeferred<Result<String>>>()

    private fun readState(message: String? = null, isError: Boolean = false) = YouTubeAccountState(
        signedIn = store.exists(),
        cookieCount = store.entryCount(),
        savedAt = store.savedAt(),
        message = message,
        isError = isError,
    )

    fun refresh(message: String? = null, isError: Boolean = false) {
        _state.value = readState(message, isError)
    }

    /** Installs the helper extension (private browsing allowed) and wires the native port. */
    suspend fun ensureHelper(): Boolean = withContext(Dispatchers.Main) {
        if (extension != null) return@withContext true
        runCatching {
            val runtime = GeckoRuntimeManager.getInstance(context).runtime
            val controller = runtime.webExtensionController
            val ext = controller.ensureBuiltIn(EXTENSION_URI, EXTENSION_ID).awaitResult()
                ?: error("helper extension returned null")
            val allowed = controller.setAllowedInPrivateBrowsing(ext, true).awaitResult() ?: ext
            ext.setMessageDelegate(this@YouTubeAccountManager, NATIVE_APP)
            allowed.setMessageDelegate(this@YouTubeAccountManager, NATIVE_APP)
            extension = allowed
            true
        }.getOrElse {
            Log.w(TAG, "YouTube sign-in helper unavailable: ${it.javaClass.simpleName}")
            false
        }
    }

    override fun onConnect(port: WebExtension.Port) {
        this.port = port
        port.setDelegate(this)
    }

    override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? = null

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        val json = (message as? JSONObject) ?: runCatching { JSONObject(message.toString()) }.getOrNull() ?: return
        val id = json.optInt("id", 0)
        val deferred = synchronized(pending) { pending.remove(id) } ?: return
        when (json.optString("type")) {
            "cookies" -> deferred.complete(Result.success(json.optString("cookies", "[]")))
            "error" -> deferred.complete(Result.failure(IllegalStateException("cookie export failed")))
        }
    }

    override fun onDisconnect(port: WebExtension.Port) {
        if (this.port == port) this.port = null
    }

    /** Asks the helper for youtube.com/google.com cookies (JSON). */
    private suspend fun requestCookiesJson(timeoutMs: Long = 15_000): String {
        if (!ensureHelper()) error("The sign-in helper could not be started.")
        return withTimeout(timeoutMs) {
            while (port == null) delay(200)
            val id = nextId.getAndIncrement()
            val deferred = CompletableDeferred<Result<String>>()
            synchronized(pending) { pending[id] = deferred }
            withContext(Dispatchers.Main) { port?.postMessage(JSONObject().put("type", "export").put("id", id)) }
            deferred.await().getOrThrow()
        }
    }

    /**
     * Exports the cookies of the sign-in session to cookies.txt. Returns a user-facing message.
     * Fails (without writing) when no signed-in Google session is present.
     */
    suspend fun exportFromBrowser(): Result<String> = runCatching {
        val json = requestCookiesJson()
        val cookies = NetscapeCookies.parseExtensionJson(json)
        val now = System.currentTimeMillis() / 1000
        val clean = NetscapeCookies.sanitize(cookies, now)
        if (!NetscapeCookies.hasLogin(clean)) {
            error("No signed-in YouTube session was found. Finish signing in first (you should see your avatar on YouTube), then tap Done.")
        }
        val n = withContext(Dispatchers.IO) { store.write(clean, now) }
        "Signed in. Saved $n YouTube/Google cookies on this phone only."
    }.onSuccess { refresh(it) }.onFailure { refresh(it.message ?: "Sign-in failed", isError = true) }

    /** Deletes cookies.txt and the YouTube/Google data of the in-app browser profile. */
    suspend fun signOut(): String {
        withContext(Dispatchers.IO) { store.clear() }
        runCatching {
            withContext(Dispatchers.Main) {
                val sc = GeckoRuntimeManager.getInstance(context).runtime.storageController
                val flags = StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES or
                    StorageController.ClearFlags.AUTH_SESSIONS
                for (domain in listOf("youtube.com", "google.com")) sc.clearDataFromBaseDomain(domain, flags).awaitResult()
            }
        }
        val msg = "Signed out. YouTube cookies deleted from this phone."
        refresh(msg)
        return msg
    }

    companion object {
        private const val TAG = "Mi5G-YouTube"
        const val EXTENSION_ID = "youtube-cookies@mi5gdl.midroid"
        const val EXTENSION_URI = "resource://android/assets/extensions/mi5g-cookies/"
        const val NATIVE_APP = "mi5gCookies"

        /** Sign-in start page: YouTube's own Google sign-in, returning to the mobile site. */
        const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=https%3A%2F%2Fm.youtube.com%2F"
        /** yt-dlp wiki: visit robots.txt after signing in, then export, then close the private window. */
        const val ROBOTS_URL = "https://www.youtube.com/robots.txt"

        @Volatile private var instance: YouTubeAccountManager? = null
        fun get(context: Context): YouTubeAccountManager = instance ?: synchronized(this) {
            instance ?: YouTubeAccountManager(context.applicationContext).also { instance = it }
        }
    }
}
