package com.mvrk.vrka.mi5g

import com.mvrk.vrka.mi5g.trim.TrimSpec
import com.mvrk.vrka.mi5g.trim.TrimSpec.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimSpecTest {
    @Test fun parsesFormats() {
        assertEquals(45.0, TrimSpec.parseTime("45")!!, 0.0)
        assertEquals(83.0, TrimSpec.parseTime("1:23")!!, 0.0)
        assertEquals(83.0, TrimSpec.parseTime("01:23")!!, 0.0)
        assertEquals(3723.0, TrimSpec.parseTime("1:02:03")!!, 0.0)
        assertEquals(65.5, TrimSpec.parseTime(" 1:05.5 ")!!, 0.0001)
    }

    @Test fun rejectsBadTimes() {
        listOf("", "abc", "1:60", "1:61:00", "1::2", "1.5:00", "-3", "1:2:3:4", "1:05.1234", ":30").forEach {
            assertNull("should reject '$it'", TrimSpec.parseTime(it))
        }
    }

    @Test fun validation() {
        assertEquals(Validation.Off, TrimSpec.validate("", ""))
        assertEquals(Validation.Off, TrimSpec.validate("0", ""))
        assertEquals(Validation.Ok(TrimSpec.Range(83.0, 125.0)), TrimSpec.validate("1:23", "2:05"))
        assertEquals(Validation.Ok(TrimSpec.Range(0.0, 30.0)), TrimSpec.validate("", "0:30"))
        assertEquals(Validation.Ok(TrimSpec.Range(30.0, null)), TrimSpec.validate("0:30", ""))
        assertTrue(TrimSpec.validate("2:00", "1:00") is Validation.Invalid)
        assertTrue(TrimSpec.validate("1:00", "1:00") is Validation.Invalid)
        assertTrue(TrimSpec.validate("x", "1:00") is Validation.Invalid)
        assertTrue(TrimSpec.validate("0:10", "y") is Validation.Invalid)
    }

    @Test fun validationAgainstDuration() {
        assertTrue(TrimSpec.validate("5:00", "", durationSeconds = 120.0) is Validation.Invalid)
        assertTrue(TrimSpec.validate("0:10", "3:00", durationSeconds = 120.0) is Validation.Invalid)
        assertTrue(TrimSpec.validate("0:10", "2:00", durationSeconds = 120.0) is Validation.Ok)
        assertTrue(TrimSpec.validate("0:10", "2:00.8", durationSeconds = 120.0) is Validation.Ok) // 1 s tolerance
    }

    @Test fun ytDlpArgs() {
        assertEquals(listOf("--download-sections", "*83-125.5"), TrimSpec.ytDlpArgs("1:23", "2:05.5", precise = false))
        assertEquals(listOf("--download-sections", "*10-inf", "--force-keyframes-at-cuts"), TrimSpec.ytDlpArgs("10", "", precise = true))
        assertEquals(listOf("--download-sections", "*0-30"), TrimSpec.ytDlpArgs("", "30", precise = false))
        assertEquals(emptyList<String>(), TrimSpec.ytDlpArgs("", "", precise = true))
        assertEquals(emptyList<String>(), TrimSpec.ytDlpArgs("2:00", "1:00", precise = true))
    }

    @Test fun formatAndLabel() {
        assertEquals("1:23", TrimSpec.format(83.0))
        assertEquals("1:02:03", TrimSpec.format(3723.0))
        assertEquals("0:05.5", TrimSpec.format(5.5))
        assertEquals("✂ 1:23–2:05", TrimSpec.label("1:23", "2:05"))
        assertEquals("✂ from 0:30 (precise)", TrimSpec.label("30", "", precise = true))
        assertNull(TrimSpec.label("", ""))
        assertTrue(TrimSpec.isTrimmed("1", "2")); assertFalse(TrimSpec.isTrimmed("", ""))
    }

    @Test fun needsPostTrim() {
        val r = TrimSpec.Range(60.0, 90.0)
        assertTrue(TrimSpec.needsPostTrim(r, actualDurationSeconds = 600.0))
        assertFalse(TrimSpec.needsPostTrim(r, actualDurationSeconds = 31.5))
        assertFalse(TrimSpec.needsPostTrim(r, actualDurationSeconds = null))
        val open = TrimSpec.Range(60.0, null)
        assertFalse("unknown source length: conservative", TrimSpec.needsPostTrim(open, 600.0))
        assertTrue(TrimSpec.needsPostTrim(open, 600.0, sourceDurationSeconds = 600.0))
        assertFalse(TrimSpec.needsPostTrim(open, 540.0, sourceDurationSeconds = 600.0))
    }

    @Test fun ffmpegFallbackArgs() {
        val r = TrimSpec.Range(83.0, 125.5)
        val copy = TrimSpec.ffmpegArgs("in.mp4", "out.mp4", r, precise = false, audioOnly = false)
        assertTrue(copy.containsAll(listOf("-ss", "83", "-i", "in.mp4", "-t", "42.5", "-c", "copy")))
        assertTrue(copy.indexOf("-ss") < copy.indexOf("-i"))
        assertEquals("out.mp4", copy.last())
        val precise = TrimSpec.ffmpegArgs("in.mp4", "out.mp4", r, precise = true, audioOnly = false)
        assertTrue(precise.containsAll(listOf("-c:v", "libx264", "-c:a", "aac")))
        assertFalse(precise.contains("copy") && precise[precise.indexOf("copy") - 1] == "-c")
        val audio = TrimSpec.ffmpegArgs("in.m4a", "out.m4a", TrimSpec.Range(0.0, 30.0), precise = true, audioOnly = true)
        assertFalse(audio.contains("-ss")); assertTrue(audio.containsAll(listOf("-c", "copy")))
    }
}
