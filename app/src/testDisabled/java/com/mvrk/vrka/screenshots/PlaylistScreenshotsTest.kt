package com.mvrk.vrka.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mvrk.vrka.AppSettings
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.JobState
import com.mvrk.vrka.VrkaTheme
import com.mvrk.vrka.mi5g.playlist.PlaylistPicker
import com.mvrk.vrka.mi5g.playlist.PlaylistProgressView
import com.mvrk.vrka.mi5g.playlist.PlaylistRules
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Mi5G Dl 1.2.0 playlist screenshots (only when -Pmi5gScreenshotsDir is given). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class PlaylistScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        val outDir = System.getProperty("mi5g.screenshots.dir")
        assumeTrue(!outDir.isNullOrBlank())
        compose.mainClock.autoAdvance = false
        compose.setContent { VrkaTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        compose.mainClock.advanceTimeBy(1500)
        var bmp: Bitmap? = null
        compose.runOnUiThread {
            val v = compose.activity.window.decorView.rootView
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
        }
        File(outDir!!).mkdirs()
        File(outDir, name).outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val list = PlaylistRules.Playlist(
        "Lo-fi Study Mix 2026",
        (1..12).map { PlaylistRules.Entry("https://www.youtube.com/watch?v=v$it", "Lo-fi track number $it — chill beats to study", null) },
    )

    @Test fun picker() = shoot("playlist-01-picker.png") {
        PlaylistPicker(list, AppSettings(), onDismiss = {}, loadThumbnails = false) { _, _, _ -> }
    }

    @Config(qualifiers = "ar-w411dp-h891dp-xxhdpi")
    @Test fun pickerArabic() = shoot("playlist-02-picker-arabic-rtl.png") {
        PlaylistPicker(list.copy(title = "قائمة دراسة هادئة"), AppSettings(), onDismiss = {}, loadThumbnails = false) { _, _, _ -> }
    }

    @Test fun progressWithFailure() = shoot("playlist-03-progress.png") {
        val jobs = (1..12).map { i ->
            val state = when { i <= 2 -> JobState.DONE; i == 3 -> JobState.FAILED; i == 4 -> JobState.DOWNLOADING; else -> JobState.QUEUED }
            DownloadJob("j$i", DownloadRequest("https://www.youtube.com/watch?v=v$i", playlistGroup = "g"), state = state,
                title = "Lo-fi track number $i", progress = if (i == 4) 0.42f else 0f, speed = if (i == 4) "2.1MiB/s" else "",
                error = if (i == 3) "HTTP Error 403: Forbidden" else "", createdAt = i.toLong())
        }
        PlaylistProgressView(list.title, PlaylistRules.progress(jobs, "g"), onRetry = {}, onDismiss = {})
    }
}
