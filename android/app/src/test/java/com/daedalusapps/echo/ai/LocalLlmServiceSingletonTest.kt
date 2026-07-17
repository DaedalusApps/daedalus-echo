package com.daedalusapps.echo.ai

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test

class LocalLlmServiceSingletonTest {

    @After
    fun resetSingleton() {
        LocalLlmService::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
    }

    @Test
    fun getInstance_returnsSameInstanceForSameContext() {
        val context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns this@mockk
        }

        val first = LocalLlmService.getInstance(context)
        val second = LocalLlmService.getInstance(context)

        assertSame(first, second)
    }
}
