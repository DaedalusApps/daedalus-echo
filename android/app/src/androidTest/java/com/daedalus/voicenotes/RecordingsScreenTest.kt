package com.daedalus.voicenotes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.daedalus.voicenotes.ble.BleManager
import com.daedalus.voicenotes.ble.BleState
import com.daedalus.voicenotes.ble.ConnectionState
import com.daedalus.voicenotes.data.model.Recording
import com.daedalus.voicenotes.ui.screens.RecordingsScreen
import com.daedalus.voicenotes.ui.theme.DaedalusTheme
import com.daedalus.voicenotes.viewmodel.DeviceViewModel
import com.daedalus.voicenotes.viewmodel.RecordingViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RecordingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val deviceViewModel = mockk<DeviceViewModel>(relaxed = true)
    private val recordingViewModel = mockk<RecordingViewModel>(relaxed = true)
    private val bleManager = mockk<BleManager>(relaxed = true)

    private val bleStateFlow = MutableStateFlow(BleState())
    private val filteredRecordingsFlow = MutableStateFlow<List<Recording>>(emptyList())
    private val searchQueryFlow = MutableStateFlow("")
    private val syncProgressFlow = MutableStateFlow<String?>(null)

    @Before
    fun setup() {
        every { deviceViewModel.bleManager } returns bleManager
        every { bleManager.bleState } returns bleStateFlow
        every { recordingViewModel.filteredRecordings } returns filteredRecordingsFlow
        every { recordingViewModel.searchQuery } returns searchQueryFlow
        every { recordingViewModel.syncProgress } returns syncProgressFlow
    }

    @Test
    fun recordingsScreen_disconnected_showsNotConnectedBanner() {
        bleStateFlow.value = BleState(connectionState = ConnectionState.DISCONNECTED)

        composeTestRule.setContent {
            DaedalusTheme {
                RecordingsScreen(
                    viewModel = deviceViewModel,
                    recordingViewModel = recordingViewModel,
                    onNavigateToNote = {},
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithText("FW920 not connected").assertIsDisplayed()
    }

    @Test
    fun recordingsScreen_connected_showsDeviceStatusRow() {
        bleStateFlow.value = BleState(
            connectionState = ConnectionState.CONNECTED,
            batteryPct = 85
        )

        composeTestRule.setContent {
            DaedalusTheme {
                RecordingsScreen(
                    viewModel = deviceViewModel,
                    recordingViewModel = recordingViewModel,
                    onNavigateToNote = {},
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithText("FW920").assertIsDisplayed()
        composeTestRule.onNodeWithText("85%").assertIsDisplayed()
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
                    viewModel = deviceViewModel,
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
                    viewModel = deviceViewModel,
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
