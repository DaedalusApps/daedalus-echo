package com.daedalusapps.echo.ai

import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AnalysisForegroundServiceTest {

    @Test
    fun constants_haveExpectedValues() {
        assertEquals("daedalus_processing_channel", AnalysisForegroundService.CHANNEL_ID)
        assertEquals(4201, AnalysisForegroundService.NOTIFICATION_ID)
        assertEquals("extra_filename", AnalysisForegroundService.EXTRA_FILENAME)
        assertEquals("extra_status", AnalysisForegroundService.EXTRA_STATUS)
        assertEquals("com.daedalusapps.echo.START_ANALYSIS_SERVICE", AnalysisForegroundService.ACTION_START)
        assertEquals("com.daedalusapps.echo.STOP_ANALYSIS_SERVICE", AnalysisForegroundService.ACTION_STOP)
    }

    @Test
    fun start_createsIntentWithStartActionAndExtras() {
        val context = mockk<Context>(relaxed = true)
        val intentSlot = slot<Intent>()
        every { context.startService(capture(intentSlot)) } returns null

        AnalysisForegroundService.start(context, "meeting.m4a", "Transcribing audio…")

        verify(atLeast = 1) {
            context.startService(any())
        }
        val intent = intentSlot.captured
        assertNotNull(intent)
        assertEquals(AnalysisForegroundService.ACTION_START, intent.action)
        assertEquals("meeting.m4a", intent.getStringExtra(AnalysisForegroundService.EXTRA_FILENAME))
        assertEquals("Transcribing audio…", intent.getStringExtra(AnalysisForegroundService.EXTRA_STATUS))
    }

    @Test
    fun stop_createsIntentWithStopAction() {
        val context = mockk<Context>(relaxed = true)
        val intentSlot = slot<Intent>()
        every { context.startService(capture(intentSlot)) } returns null

        AnalysisForegroundService.stop(context)

        verify(exactly = 1) {
            context.startService(capture(intentSlot))
        }
        val intent = intentSlot.captured
        assertNotNull(intent)
        assertEquals(AnalysisForegroundService.ACTION_STOP, intent.action)
    }

    @Test
    fun startAndStop_handleExceptionsSafely() {
        val failingContext = mockk<Context>()
        every { failingContext.startService(any()) } throws RuntimeException("Service start failed")

        // Should not throw
        AnalysisForegroundService.start(failingContext, "test.mp3", "Status")
        AnalysisForegroundService.stop(failingContext)
    }
}
