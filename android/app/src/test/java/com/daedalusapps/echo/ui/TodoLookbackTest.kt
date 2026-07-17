package com.daedalusapps.echo.ui

import com.daedalusapps.echo.ui.screens.LOOKBACK_OPTIONS
import com.daedalusapps.echo.ui.screens.LookbackSelection
import com.daedalusapps.echo.ui.screens.TODO_LOOKBACK_HOURS_DEFAULT
import com.daedalusapps.echo.ui.screens.TODO_LOOKBACK_HOURS_KEY
import com.daedalusapps.echo.ui.screens.lookbackOptionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoLookbackTest {

    @Test
    fun `preset hours map to Standard selection`() {
        assertEquals(LookbackSelection.Standard(24L), lookbackOptionFor(24L))
        assertEquals(LookbackSelection.Standard(72L), lookbackOptionFor(72L))
        assertEquals(LookbackSelection.Standard(168L), lookbackOptionFor(168L))
        assertEquals(LookbackSelection.Standard(-1L), lookbackOptionFor(-1L))
    }

    @Test
    fun `nonstandard hours map to Custom selection prefilled with that value`() {
        assertEquals(LookbackSelection.Custom(500L), lookbackOptionFor(500L))
    }

    @Test
    fun `default lookback constant is 72 hours and is a preset option`() {
        assertEquals(72L, TODO_LOOKBACK_HOURS_DEFAULT)
        assertEquals(LookbackSelection.Standard(72L), lookbackOptionFor(TODO_LOOKBACK_HOURS_DEFAULT))
    }

    @Test
    fun `absent pref falls back to default via getLong-style contract`() {
        // Simulates SharedPreferences#getLong(key, default) behavior when the key is absent.
        val storedPrefs = emptyMap<String, Long>()
        val resolvedHours = storedPrefs[TODO_LOOKBACK_HOURS_KEY] ?: TODO_LOOKBACK_HOURS_DEFAULT
        assertEquals(TODO_LOOKBACK_HOURS_DEFAULT, resolvedHours)
        assertEquals(LookbackSelection.Standard(72L), lookbackOptionFor(resolvedHours))
    }

    @Test
    fun `every preset option hours round-trips to itself as Standard`() {
        LOOKBACK_OPTIONS.forEach { option ->
            assertTrue(lookbackOptionFor(option.hours) == LookbackSelection.Standard(option.hours))
        }
    }
}
