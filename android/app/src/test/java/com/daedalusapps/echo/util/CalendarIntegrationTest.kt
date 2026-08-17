package com.daedalusapps.echo.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CalendarIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `addToCalendar with explicit parameters creates expected insert intent when activity resolves`() {
        val resolveIntent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
        }
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.google.android.calendar"
                name = "com.google.android.calendar.LaunchActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(resolveIntent, resolveInfo)

        val beginTime = 1700000000000L
        CalendarIntegration.addToCalendar(
            context = context,
            title = "Review architecture",
            description = "Discuss database migration",
            beginTimeMs = beginTime
        )

        val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        val intent = shadowApp.nextStartedActivity
        assertNotNull("Expected an activity to be started", intent)
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals("Review architecture", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals("Discuss database migration", intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
        assertEquals(beginTime, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L))
        assertEquals(beginTime + 3600000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L))
        assertTrue(
            "Expected FLAG_ACTIVITY_NEW_TASK flag to be set",
            (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0
        )
    }

    @Test
    fun `addToCalendar with default parameters uses empty description and current time`() {
        val resolveIntent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
        }
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.google.android.calendar"
                name = "com.google.android.calendar.LaunchActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(resolveIntent, resolveInfo)

        val beforeMs = System.currentTimeMillis()
        CalendarIntegration.addToCalendar(
            context = context,
            title = "Quick task"
        )
        val afterMs = System.currentTimeMillis()

        val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        val intent = shadowApp.nextStartedActivity
        assertNotNull("Expected an activity to be started", intent)
        assertEquals("Quick task", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals("", intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
        val beginTime = intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L)
        assertTrue("Begin time $beginTime should be between $beforeMs and $afterMs", beginTime in beforeMs..afterMs)
        assertEquals(beginTime + 3600000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L))
    }

    @Test
    fun `addToCalendar when no activity resolves creates chooser fallback with insert intent`() {
        val beginTime = 1700000000000L
        CalendarIntegration.addToCalendar(
            context = context,
            title = "Fallback task",
            description = "Some description",
            beginTimeMs = beginTime
        )

        val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        val chooserIntent = shadowApp.nextStartedActivity
        assertNotNull("Expected chooser activity to be started", chooserIntent)
        assertEquals(Intent.ACTION_CHOOSER, chooserIntent.action)
        assertTrue(
            "Expected chooser to have FLAG_ACTIVITY_NEW_TASK",
            (chooserIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0
        )

        val targetIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            chooserIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            chooserIntent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        assertNotNull("Expected inner intent in chooser", targetIntent)
        assertEquals(Intent.ACTION_INSERT, targetIntent?.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, targetIntent?.data)
        assertEquals("Fallback task", targetIntent?.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals("Some description", targetIntent?.getStringExtra(CalendarContract.Events.DESCRIPTION))
        assertEquals(beginTime, targetIntent?.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L))
        assertEquals(beginTime + 3600000L, targetIntent?.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L))
        assertTrue(
            "Expected inner intent to have FLAG_ACTIVITY_NEW_TASK",
            (targetIntent?.flags?.and(Intent.FLAG_ACTIVITY_NEW_TASK)) != 0
        )
    }
}
