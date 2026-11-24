package com.mvrk.vrka.screenshots

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.mvrk.vrka.HomeScreen
import com.mvrk.vrka.AppSettings
import com.mvrk.vrka.RuntimeStatus
import com.mvrk.vrka.SettingsRepository
import com.mvrk.vrka.VrkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Mi5G Dl: renders the Download (home) screen to PNG on the JVM.
 * Only runs when -Dmi5g.screenshots.dir=<dir> is passed (skipped otherwise).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ScreenshotRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renderHomeScreen() {
        val outDir = System.getProperty("mi5g.screenshots.dir")
        assumeTrue(!outDir.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repo = SettingsRepository(context, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VrkaTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
                    HomeScreen(
                        settings = AppSettings(),
                        repository = repo,
                        runtime = RuntimeStatus(),
                        onEnqueue = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        var bmp: Bitmap? = null
        compose.runOnUiThread {
            val view = compose.activity.window.decorView.rootView
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bmp = b
        }
        val dir = File(outDir!!).apply { mkdirs() }
        File(dir, "home-download-dark.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
