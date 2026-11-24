package com.mvrk.vrka

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Confirms the app does not force English, and Arabic resources resolve when the
 * device configuration is Arabic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocaleBrandingResourceTest {

    @Test
    fun applyLocaleFollowsTheSystemWhenNoOverrideIsStored() {
        val base = ApplicationProvider.getApplicationContext<Application>()
        val applied = LocaleHelper.applyLocale(base)
        assertEquals(base, applied)
        assertTrue(LocaleHelper.getActiveLanguageTag(base).isEmpty())
    }

    @Test
    fun arabicConfigurationLoadsArabicUiStrings() {
        RuntimeEnvironment.setQualifiers("ar")
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("Mi5G Dl", ctx.getString(R.string.app_name))
        val share = ctx.getString(R.string.share_label)
        val interrupted = ctx.getString(R.string.notification_downloads_interrupted_text)
        assertEquals("التنزيل عبر Mi5G Dl", share)
        assertTrue(interrupted.contains("افتح"))
        assertFalse(share.contains("Download with"))
        assertNotEquals(
            "Arabic configuration must not fall back to the English share label",
            "Download with Mi5G Dl",
            share,
        )
    }
}
