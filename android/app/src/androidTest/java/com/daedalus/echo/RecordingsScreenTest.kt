package com.daedalus.echo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.daedalus.echo.data.model.Recording
import com.daedalus.echo.ui.screens.RecordingsScreen
import com.daedalus.echo.ui.theme.DaedalusTheme
import com.daedalus.echo.viewmodel.RecordingViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RecordingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val recordingViewModel = mockk<RecordingViewModel>(relaxed = true)

    private val filteredRecordingsFlow = MutableStateFlow<List<Recording>>(emptyList())
    private val searchQueryFlow = MutableStateFlow("")
    private val syncProgressFlow = MutableStateFlow<String?>(null)

    @Before
    fun setup() {
        every { recordingViewModel.filteredRecordings } returns filteredRecordingsFlow
        every { recordingViewModel.searchQuery } returns searchQueryFlow
        every { recordingViewModel.syncProgress } returns syncProgressFlow
    }

    @Test
    fun recordingsScreen_recordingCard_displaysTitle_and_summary() {
        val recording = Recording(
            filename = "20260524213434.mp3",
            title = "Project Meeting",
            shortSummary = "Discussed the new global mind map."
        )
        filteredRecordingsFlow.value = listOf(recording)

        composeTestRule.setContent {
            DaedalusTheme {
                RecordingsScreen(
                    recordingViewModel = recordingViewModel,
                    onNavigateToNote = {},
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Project Meeting").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discussed the new global mind map.").assertIsDisplayed()
    }

    @Test
    fun recordingsScreen_longPress_entersSelectionMode() {
        val recording1 = Recording(filename = "file1.mp3", title = "Note 1")
        val recording2 = Recording(filename = "file2.mp3", title = "Note 2")
        filteredRecordingsFlow.value = listOf(recording1, recording2)

        composeTestRule.setContent {
            DaedalusTheme {
                RecordingsScreen(
                    recordingViewModel = recordingViewModel,
                    onNavigateToNote = {},
                    onBack = {}
                )
            }
        }

        // Long press first item
        composeTestRule.onNodeWithText("Note 1").performTouchInput { longClick() }

        // Assert selection mode UI
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Select item").onFirst().assertExists()
        composeTestRule.onAllNodesWithContentDescription("Select item")[0].assertIsOn()
    }
}
