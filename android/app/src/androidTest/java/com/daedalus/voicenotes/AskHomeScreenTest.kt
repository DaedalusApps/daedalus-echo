package com.daedalus.voicenotes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.daedalus.voicenotes.ui.screens.AskHomeScreen
import com.daedalus.voicenotes.ui.theme.DaedalusTheme
import com.daedalus.voicenotes.viewmodel.DeviceViewModel
import com.daedalus.voicenotes.viewmodel.RecordingViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AskHomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val deviceViewModel = mockk<DeviceViewModel>(relaxed = true)
    private val recordingViewModel = mockk<RecordingViewModel>(relaxed = true)

    private val libraryAnswerFlow = MutableStateFlow<String?>(null)
    private val libraryQuestionFlow = MutableStateFlow("")
    private val isAskingFlow = MutableStateFlow(false)
    private val globalGraphFlow = MutableStateFlow(com.daedalus.voicenotes.ui.mindmap.GlobalGraph(emptyList(), emptyList()))
    private val bleStateFlow = MutableStateFlow(com.daedalus.voicenotes.ble.BleState())

    @Before
    fun setup() {
        every { recordingViewModel.libraryAnswer } returns libraryAnswerFlow
        every { recordingViewModel.libraryQuestion } returns libraryQuestionFlow
        every { recordingViewModel.isAsking } returns isAskingFlow
        every { recordingViewModel.globalGraph } returns globalGraphFlow
        every { recordingViewModel.aiError } returns MutableStateFlow(null)
        every { recordingViewModel.librarySources } returns MutableStateFlow(emptyList())
        every { recordingViewModel.exportIntent } returns MutableStateFlow(null)
        
        every { deviceViewModel.bleManager.bleState } returns bleStateFlow
    }

    @Test
    fun askHomeScreen_displaysSearchAndGraph() {
        composeTestRule.setContent {
            DaedalusTheme {
                AskHomeScreen(
                    recordingViewModel = recordingViewModel,
                    deviceViewModel = deviceViewModel,
                    onNavigateToNote = {},
                    onNavigateToRecordings = {},
                    onNavigateToExpandedMap = {},
                    onNavigateToSettings = {}
                )
            }
        }

        // Verify search input
        composeTestRule.onNodeWithText("Ask anything across all your notes…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ask").assertIsDisplayed()

        // Verify Knowledge Graph section
        composeTestRule.onNodeWithText("Knowledge Graph").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand graph").assertIsDisplayed()
    }

    @Test
    fun askHomeScreen_showsAnswerCard_whenAnswerProvided() {
        libraryAnswerFlow.value = "This is a test answer."
        libraryQuestionFlow.value = "Test question?"

        composeTestRule.setContent {
            DaedalusTheme {
                AskHomeScreen(
                    recordingViewModel = recordingViewModel,
                    deviceViewModel = deviceViewModel,
                    onNavigateToNote = {},
                    onNavigateToRecordings = {},
                    onNavigateToExpandedMap = {},
                    onNavigateToSettings = {}
                )
            }
        }

        composeTestRule.onNodeWithText("This is a test answer.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share").assertIsDisplayed()
    }
}
