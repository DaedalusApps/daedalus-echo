package com.daedalusapps.echo.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SwipeToDeleteCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysContent() {
        composeTestRule.setContent {
            SwipeToDeleteCard(
                confirmTitle = "Delete Item?",
                confirmText = "This cannot be undone.",
                onDelete = {}
            ) {
                Text("Item Content", modifier = Modifier.fillMaxWidth())
            }
        }

        composeTestRule.onNodeWithText("Item Content").assertIsDisplayed()
    }

    @Test
    fun swipeLeft_showsConfirmationDialog_andConfirmingInvokesOnDelete() {
        var deleted = false
        composeTestRule.setContent {
            SwipeToDeleteCard(
                confirmTitle = "Delete Item?",
                confirmText = "This cannot be undone.",
                onDelete = { deleted = true }
            ) {
                Text("Item Content", modifier = Modifier.fillMaxWidth())
            }
        }

        composeTestRule.onNodeWithText("Item Content").performTouchInput {
            swipeLeft()
        }

        composeTestRule.onNodeWithText("Delete Item?").assertIsDisplayed()
        composeTestRule.onNodeWithText("This cannot be undone.").assertIsDisplayed()

        composeTestRule.onNodeWithText("Delete").performClick()

        assertTrue(deleted)
        composeTestRule.onNodeWithText("Delete Item?").assertDoesNotExist()
    }

    @Test
    fun swipeLeft_dismissingDialogDoesNotInvokeOnDelete() {
        var deleted = false
        composeTestRule.setContent {
            SwipeToDeleteCard(
                confirmTitle = "Delete Item?",
                confirmText = "This cannot be undone.",
                onDelete = { deleted = true }
            ) {
                Text("Item Content", modifier = Modifier.fillMaxWidth())
            }
        }

        composeTestRule.onNodeWithText("Item Content").performTouchInput {
            swipeLeft()
        }

        composeTestRule.onNodeWithText("Delete Item?").assertIsDisplayed()

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertFalse(deleted)
        composeTestRule.onNodeWithText("Delete Item?").assertDoesNotExist()
    }
}
