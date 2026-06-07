package com.daedalus.echo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.daedalus.echo.ui.mindmap.GlobalGraph
import com.daedalus.echo.ui.mindmap.GraphNode
import com.daedalus.echo.ui.screens.GlobalMindMapScreen
import com.daedalus.echo.ui.theme.DaedalusTheme
import org.junit.Rule
import org.junit.Test

class GlobalMindMapScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun globalMindMapScreen_displaysTopicsAndRecordings() {
        val graph = GlobalGraph(
            nodes = listOf(
                GraphNode("r1", "Recording One", GraphNode.Type.RECORDING),
                GraphNode("t1", "Topic AI", GraphNode.Type.TOPIC)
            ),
            edges = emptyList()
        )

        composeTestRule.setContent {
            DaedalusTheme {
                GlobalMindMapScreen(
                    graph = graph,
                    onNavigateToNote = {},
                    onBack = {}
                )
            }
        }

        // Nodes should be rendered on canvas (we check for labels)
        composeTestRule.onNodeWithText("Recording One").assertExists()
        composeTestRule.onNodeWithText("Topic AI").assertExists()
    }

    @Test
    fun globalMindMapScreen_showsEmptyState() {
        composeTestRule.setContent {
            DaedalusTheme {
                GlobalMindMapScreen(
                    graph = GlobalGraph(emptyList(), emptyList()),
                    onNavigateToNote = {},
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithText("No topics analyzed yet.").assertIsDisplayed()
    }
}
