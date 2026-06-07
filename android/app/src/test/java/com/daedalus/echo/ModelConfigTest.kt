package com.daedalus.echo

import android.content.Context
import com.daedalus.echo.ai.selectedModel
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelConfigTest {

    @Test
    fun selectedModel_alwaysReturnsGemma3_1B() {
        val context = mockk<Context>(relaxed = true)
        
        // It should ignore whatever is in prefs and just return the hardcoded one
        val model = selectedModel(context)
        
        assertEquals("gemma3_1b", model.id)
    }
}
