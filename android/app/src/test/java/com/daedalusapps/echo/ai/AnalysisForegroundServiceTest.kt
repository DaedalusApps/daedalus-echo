package com.daedalusapps.echo.ai

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AnalysisForegroundServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

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
        AnalysisForegroundService.start(context, "meeting.m4a", "Transcribing audio…")

        val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        val intent = shadowApp.nextStartedService
        assertNotNull(intent)
        assertEquals(AnalysisForegroundService.ACTION_START, intent.action)
        assertEquals("meeting.m4a", intent.getStringExtra(AnalysisForegroundService.EXTRA_FILENAME))
        assertEquals("Transcribing audio…", intent.getStringExtra(AnalysisForegroundService.EXTRA_STATUS))
    }

    @Test
    fun stop_createsIntentWithStopAction() {
        AnalysisForegroundService.stop(context)

        val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        val intent = shadowApp.nextStartedService
        assertNotNull(intent)
        assertEquals(AnalysisForegroundService.ACTION_STOP, intent.action)
    }

    @Test
    fun startAndStop_handleExceptionsSafely() {
        val failingContext = mockk<Context>()
        every { failingContext.startService(any()) } throws RuntimeException("Service start failed")
        every { failingContext.startForegroundService(any()) } throws RuntimeException("Service start failed")

        // Should not throw
        AnalysisForegroundService.start(failingContext, "test.mp3", "Status")
        AnalysisForegroundService.stop(failingContext)
    }
}
