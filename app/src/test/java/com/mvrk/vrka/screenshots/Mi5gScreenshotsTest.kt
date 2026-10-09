package com.mvrk.vrka.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mvrk.vrka.AppSettings
import com.mvrk.vrka.VrkaSectionContainer
import com.mvrk.vrka.VrkaTheme
import com.mvrk.vrka.VrkaTokens
import com.mvrk.vrka.mi5g.share.ShareSheetContent
import com.mvrk.vrka.mi5g.sync.SyncConfig
import com.mvrk.vrka.mi5g.ui.SyncSectionContent
import com.mvrk.vrka.mi5g.ui.TrimEditor
import com.mvrk.vrka.mi5g.ui.UpdateSectionContent
import com.mvrk.vrka.mi5g.update.UpdateDownload
import com.mvrk.vrka.mi5g.update.UpdateRules
import com.mvrk.vrka.mi5g.update.UpdateUiState
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Mi5G Dl 1.1.0 screenshots (only when -Pmi5gScreenshotsDir is given). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class Mi5gScreenshotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        val outDir = System.getProperty("mi5g.screenshots.dir")
        assumeTrue(!outDir.isNullOrBlank())
        compose.mainClock.autoAdvance = false
        compose.setContent { VrkaTheme { content() } }
        compose.mainClock.advanceTimeBy(1500)
        var bmp: Bitmap? = null
        compose.runOnUiThread {
            val view = compose.activity.window.decorView.rootView
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bmp = b
        }
        val dir = File(outDir!!).apply { mkdirs() }
        File(dir, name).outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }

    @Composable
    private fun Heading(text: String) = Text(text.uppercase(), color = VrkaTokens.TextTertiary, modifier = Modifier.padding(top = 8.dp, start = 4.dp))

    @Composable
    private fun SheetOver(content: @Composable () -> Unit) {
        // Dimmed "source app" behind a bottom sheet, like the real translucent ShareActivity.
        Box(Modifier.fillMaxSize().background(Color(0xFF20232A))) {
            Text("  Source app (e.g. YouTube / X)", color = Color(0xFF9AA0A6), modifier = Modifier.statusBarsPadding().padding(16.dp))
            Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = VrkaTokens.SurfaceCard,
            ) { Box(Modifier.padding(bottom = 24.dp)) { content() } }
        }
    }

    private val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test fun shareSheet() = shoot("01-share-sheet.png") {
        SheetOver { ShareSheetContent(url, AppSettings(), onProbe = null, onDownload = {}, onAdvanced = {}, initialTitle = "Rick Astley - Never Gonna Give You Up") }
    }

    @Test fun shareSheetTrim() = shoot("02-share-sheet-trim.png") {
        SheetOver {
            ShareSheetContent(url, AppSettings(), onProbe = null, onDownload = {}, onAdvanced = {},
                initialTrimOpen = true, initialDuration = 213.0, initialTitle = "Rick Astley - Never Gonna Give You Up")
        }
    }

    @Test fun trimEditor() = shoot("03-download-trim-editor.png") {
        Page {
            Heading("✂ Trim (Download screen)")
            VrkaSectionContainer {
                var start by remember { mutableStateOf("0:43") }
                var end by remember { mutableStateOf("1:25.5") }
                var precise by remember { mutableStateOf(true) }
                TrimEditor(start, end, precise, 213.0, false, "Length 3:33 · Rick Astley - Never Gonna Give You Up",
                    { start = it }, { end = it }, { precise = it }, onProbe = {}, modifier = Modifier.padding(vertical = 10.dp))
            }
            Heading("Validation")
            VrkaSectionContainer {
                TrimEditor("2:10", "1:00", false, null, false, null, {}, {}, {}, onProbe = {}, modifier = Modifier.padding(vertical = 10.dp))
            }
        }
    }

    private val release = UpdateRules.Release(
        tag = "v1.2.0", version = "1.2.0", name = "Mi5G Dl 1.2.0",
        notes = "• Example release notes\n• ملاحظات الإصدار بالعربية", publishedAt = "2026-10-09T10:00:00Z",
        apk = UpdateRules.Asset(1, "Mi5G-Dl-1.2.0-arm64.apk", 31_500_000, "https://api.github.com/repos/al3nkboot/mi5g-dl/releases/assets/1", null),
        updateJson = null, htmlUrl = "",
    )

    @Test fun updatesNoToken() = shoot("04-settings-updates-no-token.png") {
        Page {
            Heading("Mi5G Dl App Updates")
            UpdateSectionContent(
                state = UpdateUiState(installedVersionName = "1.1.1 (based on VRKA 5.0.2)", installedVersionCode = 3,
                    lastCheckAt = System.currentTimeMillis() - 60_000, message = "You're up to date (1.1.1 is the latest)."),
                dailyCheck = true, installMessage = null, onCheck = {}, onDownload = {}, onCancel = {}, onInstall = {},
                onSaveToken = {}, onClearToken = {}, onDailyCheck = {},
            )
        }
    }

    @Test fun updatesRateLimitedAdvanced() = shoot("04b-settings-updates-rate-limited-advanced.png") {
        Page {
            Heading("Mi5G Dl App Updates")
            val now = System.currentTimeMillis()
            UpdateSectionContent(
                state = UpdateUiState(installedVersionName = "1.1.1 (based on VRKA 5.0.2)", installedVersionCode = 3,
                    message = UpdateRules.rateLimitMessage(now + 25 * 60_000, now, hasToken = false), isError = true,
                    rateLimitedUntil = now + 25 * 60_000),
                dailyCheck = true, installMessage = null, onCheck = {}, onDownload = {}, onCancel = {}, onInstall = {},
                onSaveToken = {}, onClearToken = {}, onDailyCheck = {}, initialShowAdvanced = true, initialShowHelp = true,
            )
        }
    }

    @Test fun updatesAvailable() = shoot("05-settings-update-downloading.png") {
        Page {
            Heading("Mi5G Dl App Updates")
            UpdateSectionContent(
                state = UpdateUiState(hasToken = true, tokenHint = "github_pat_11…x9Qa", installedVersionName = "1.1.1 (based on VRKA 5.0.2)",
                    installedVersionCode = 3, lastCheckAt = System.currentTimeMillis() - 60_000, release = release, remoteVersionCode = 3,
                    updateAvailable = true, message = "Mi5G Dl 1.2.0 is available.", download = UpdateDownload.Running(12_400_000, 31_500_000)),
                dailyCheck = true, installMessage = null, onCheck = {}, onDownload = {}, onCancel = {}, onInstall = {},
                onSaveToken = {}, onClearToken = {}, onDailyCheck = {},
            )
        }
    }

    @Test fun syncSettings() = shoot("06-settings-sync.png") {
        Page {
            Heading("Sync (Google Drive file) — not set up")
            SyncSectionContent(SyncConfig(), false, {}, {}, {}, {}, {}, {})
            Heading("Sync (Google Drive file) — connected")
            SyncSectionContent(
                SyncConfig(fileUri = "content://x", fileLabel = "mi5g-dl-sync.json", fileId = "3f6c2a9e-8b1d-4c57-9e0a-5d2f7b81c4aa",
                    deviceName = "Samsung SM-S918B", autoOnOpen = true, intervalHours = 12, lastSyncAt = System.currentTimeMillis() - 5 * 60_000,
                    lastOk = true, lastMessage = "↓ 2 settings, 5 history, 0 deleted · ↑ written", otherDevices = listOf("Google Pixel 8")),
                false, {}, {}, {}, {}, {}, {},
            )
        }
    }

    @Test fun youtubeAccount() = shoot("07-settings-youtube-account.png") {
        Page {
            Heading("YouTube account (optional) — signed out")
            com.mvrk.vrka.mi5g.ui.YouTubeAccountSectionContent(com.mvrk.vrka.mi5g.youtube.YouTubeAccountState(), {}, {})
            Heading("YouTube account (optional) — signed in")
            com.mvrk.vrka.mi5g.ui.YouTubeAccountSectionContent(
                com.mvrk.vrka.mi5g.youtube.YouTubeAccountState(signedIn = true, cookieCount = 23,
                    savedAt = System.currentTimeMillis() - 2 * 60_000, message = "Signed in — 23 YouTube/Google cookies saved on this phone."),
                {}, {},
            )
        }
    }
}
