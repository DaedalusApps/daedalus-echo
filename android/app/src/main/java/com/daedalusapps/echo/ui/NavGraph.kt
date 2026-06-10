package com.daedalusapps.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.daedalusapps.echo.ui.screens.AskHomeScreen
import com.daedalusapps.echo.ui.screens.GlobalMindMapScreen
import com.daedalusapps.echo.ui.screens.ModelDownloadScreen
import com.daedalusapps.echo.ui.screens.NoteDetailScreen
import com.daedalusapps.echo.ui.screens.PromptEditorScreen
import com.daedalusapps.echo.ui.screens.RecordingsScreen
import com.daedalusapps.echo.ui.screens.SettingsScreen
import com.daedalusapps.echo.ui.screens.SplashScreen
import com.daedalusapps.echo.viewmodel.RecordingViewModel

@Composable
fun NavGraph(
    navController: NavHostController,
    recordingViewModel: RecordingViewModel
) {
    NavHost(navController = navController, startDestination = "splash") {

        composable("splash") {
            SplashScreen { modelReady ->
                navController.navigate(if (modelReady) "home" else "model_download") {
                    popUpTo("splash") { inclusive = true }
                }
            }
        }

        composable("model_download") {
            ModelDownloadScreen(
                onReady = {
                    navController.navigate("home") {
                        popUpTo("model_download") { inclusive = true }
                    }
                }
            )
        }

        composable("home") {
            AskHomeScreen(
                recordingViewModel = recordingViewModel,
                onNavigateToNote = { filename -> navController.navigate("note/$filename") },
                onNavigateToRecordings = { navController.navigate("recordings") },
                onNavigateToExpandedMap = { navController.navigate("global_mind_map") },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }

        composable("recordings") {
            RecordingsScreen(
                recordingViewModel = recordingViewModel,
                onNavigateToNote = { filename -> navController.navigate("note/$filename") },
                onBack = { navController.popBackStack() }
            )
        }

        composable("global_mind_map") {
            val graph by recordingViewModel.globalGraph.collectAsState()
            GlobalMindMapScreen(
                graph = graph,
                onNavigateToNote = { filename -> navController.navigate("note/$filename") },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "note/{filename}",
            arguments = listOf(navArgument("filename") { type = NavType.StringType })
        ) { backStackEntry ->
            NoteDetailScreen(
                filename = backStackEntry.arguments?.getString("filename") ?: "",
                recordingViewModel = recordingViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                recordingViewModel = recordingViewModel,
                onBack = { navController.popBackStack() },
                onNavigateToPromptEditor = { navController.navigate("prompt_editor") }
            )
        }

        composable("prompt_editor") {
            PromptEditorScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
