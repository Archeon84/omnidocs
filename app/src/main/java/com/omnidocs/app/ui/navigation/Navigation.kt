package com.omnidocs.app.ui.navigation

import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.Alignment
import com.omnidocs.app.ui.agent.AgentJobsScreen
import com.omnidocs.app.ui.screens.auth.AuthScreen
import com.omnidocs.app.ui.screens.ask.AskNotesScreen
import com.omnidocs.app.ui.screens.editor.EditorScreen
import com.omnidocs.app.ui.screens.feed.FeedScreen
import com.omnidocs.app.ui.screens.graph.GraphScreen
import com.omnidocs.app.ui.screens.home.HomeScreen
import com.omnidocs.app.ui.screens.ocr.OcrScreen
import com.omnidocs.app.ui.screens.privacy.PrivacyDashboardScreen
import com.omnidocs.app.ui.screens.recordings.RecordingsScreen
import com.omnidocs.app.ui.screens.settings.SettingsScreen
import com.omnidocs.app.ui.screens.settings.PrivacySettingsScreen
import com.omnidocs.app.ui.screens.tasks.TaskListScreen
import com.omnidocs.app.ui.screens.voice.VoiceCaptureOverlay
import com.omnidocs.app.ui.theme.MotionTokens
import com.omnidocs.app.ui.theme.screenEnterTransition
import com.omnidocs.app.ui.theme.screenExitTransition
import com.omnidocs.app.ui.theme.screenPopEnterTransition
import com.omnidocs.app.ui.theme.screenPopExitTransition

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Editor : Screen("editor?noteId={noteId}&highlight={highlight}") {
        fun createRoute(noteId: String? = null, highlight: String? = null): String {
            return when {
                noteId != null && highlight != null -> "editor?noteId=$noteId&highlight=${android.net.Uri.encode(highlight)}"
                noteId != null -> "editor?noteId=$noteId"
                else -> "editor"
            }
        }
    }
    object Ocr : Screen("ocr")
    object Settings : Screen("settings")
    object Feed : Screen("feed")
    object Auth : Screen("auth")
    object Voice : Screen("voice")
    object Graph : Screen("graph")
    object Tasks : Screen("tasks")
    object Privacy : Screen("privacy")
    object Recordings : Screen("recordings")
    object Ask : Screen("ask")
    object AgentJobs : Screen("agent_jobs")
    object PrivacyDashboard : Screen("privacy_dashboard")
}

@Composable
fun NotesNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(
            route = Screen.Home.route,
            enterTransition = { screenEnterTransition() },
            exitTransition = { screenExitTransition() },
            popEnterTransition = { screenPopEnterTransition() },
            popExitTransition = { screenPopExitTransition() }
        ) {
            HomeScreen(
                onNoteClick = { noteId ->
                    navController.navigate(Screen.Editor.createRoute(noteId))
                },
                onNewNote = {
                    navController.navigate(Screen.Editor.createRoute())
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onFeedClick = {
                    navController.navigate(Screen.Feed.route)
                },
                onVoiceCapture = {
                    navController.navigate(Screen.Voice.route)
                },
                onGraphClick = {
                    navController.navigate(Screen.Graph.route)
                },
                onTasksClick = {
                    navController.navigate(Screen.Tasks.route)
                },
                onRecordingsClick = {
                    navController.navigate(Screen.Recordings.route)
                },
                onAskNotesClick = {
                    navController.navigate(Screen.Ask.route)
                }
            )
        }

        composable(
            route = Screen.Voice.route,
            enterTransition = { slideInVertically(initialOffsetY = { it }) + fadeIn() },
            exitTransition = { slideOutVertically(targetOffsetY = { it }) + fadeOut() },
            popEnterTransition = { slideInVertically(initialOffsetY = { it }) + fadeIn() },
            popExitTransition = { slideOutVertically(targetOffsetY = { it }) + fadeOut() }
        ) {
            VoiceCaptureOverlay(
                onNoteCreated = { noteId ->
                    navController.popBackStack()
                    navController.navigate(Screen.Editor.createRoute(noteId))
                },
                onDismiss = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Graph.route,
            enterTransition = { screenEnterTransition() },
            exitTransition = { screenExitTransition() },
            popEnterTransition = { screenPopEnterTransition() },
            popExitTransition = { screenPopExitTransition() }
        ) {
            GraphScreen(
                onNavigateBack = { navController.popBackStack() },
                onNodeTap = { noteId -> navController.navigate(Screen.Editor.createRoute(noteId)) },
                navController = navController
            )
        }

        composable(
            route = Screen.Editor.route,
            arguments = listOf(
                navArgument("noteId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("highlight") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
            enterTransition = {
                fadeIn(animationSpec = tween(MotionTokens.DURATION_MS)) +
                    expandVertically(
                        animationSpec = tween(MotionTokens.DURATION_MS),
                        expandFrom = Alignment.Top
                    )
            },
            exitTransition = {
                fadeOut(animationSpec = tween(300)) +
                    shrinkVertically(
                        animationSpec = tween(300),
                        shrinkTowards = Alignment.Top
                    )
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(MotionTokens.DURATION_MS)) +
                    expandVertically(
                        animationSpec = tween(MotionTokens.DURATION_MS),
                        expandFrom = Alignment.Top
                    )
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(300)) +
                    shrinkVertically(
                        animationSpec = tween(300),
                        shrinkTowards = Alignment.Top
                    )
            }
        ) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId")
            val highlight = backStackEntry.arguments?.getString("highlight")
            EditorScreen(
                noteId = noteId,
                highlightText = highlight,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onOcrClick = {
                    navController.navigate(Screen.Ocr.route)
                },
                navController = navController
            )
        }

        composable(
            route = Screen.Ocr.route,
            enterTransition = { screenEnterTransition() },
            exitTransition = { screenExitTransition() },
            popEnterTransition = { screenPopEnterTransition() },
            popExitTransition = { screenPopExitTransition() }
        ) {
            OcrScreen(
                onTextExtracted = { text ->
                    // Pass text back to editor via savedStateHandle
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("ocrText", text)
                    navController.popBackStack()
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.Settings.route,
            enterTransition = { screenEnterTransition() },
            exitTransition = { screenExitTransition() },
            popEnterTransition = { screenPopEnterTransition() },
            popExitTransition = { screenPopExitTransition() }
        ) { backStackEntry ->
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onFeedClick = {
                    navController.navigate(Screen.Feed.route)
                },
                onAuthClick = {
                    navController.navigate(Screen.Auth.route)
                },
                onPrivacyClick = {
                    navController.navigate(Screen.Privacy.route)
                },
                onAgentJobsClick = {
                    navController.navigate(Screen.AgentJobs.route)
                },
                lifecycleOwner = backStackEntry
            )
        }

        composable(
            route = Screen.Feed.route,
            enterTransition = { screenEnterTransition() },
            exitTransition = { screenExitTransition() },
            popEnterTransition = { screenPopEnterTransition() },
            popExitTransition = { screenPopExitTransition() }
        ) {
            FeedScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.Auth.route,
            enterTransition = { screenEnterTransition() },
            exitTransition = { screenExitTransition() },
            popEnterTransition = { screenPopEnterTransition() },
            popExitTransition = { screenPopExitTransition() }
        ) {
            AuthScreen(
                onAuthSuccess = {
                    navController.popBackStack()
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.Tasks.route,
            enterTransition = { screenEnterTransition() },
            exitTransition = { screenExitTransition() },
            popEnterTransition = { screenPopEnterTransition() },
            popExitTransition = { screenPopExitTransition() }
        ) {
            TaskListScreen(
                onNoteClick = { noteId -> navController.navigate(Screen.Editor.createRoute(noteId)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Privacy.route,
            enterTransition = { screenEnterTransition() },
            exitTransition = { screenExitTransition() },
            popEnterTransition = { screenPopEnterTransition() },
            popExitTransition = { screenPopExitTransition() }
        ) {
            PrivacySettingsScreen(
                onBack = { navController.popBackStack() },
                onDashboardClick = { navController.navigate(Screen.PrivacyDashboard.route) }
            )
        }

        composable(
            route = Screen.Recordings.route,
            enterTransition = { screenEnterTransition() },
            exitTransition = { screenExitTransition() },
            popEnterTransition = { screenPopEnterTransition() },
            popExitTransition = { screenPopExitTransition() }
        ) {
            RecordingsScreen(
                onBack = { navController.popBackStack() },
                onNoteClick = { noteId -> navController.navigate(Screen.Editor.createRoute(noteId)) }
            )
        }

        composable(
            route = Screen.Ask.route,
            enterTransition = { screenEnterTransition() },
            exitTransition = { screenExitTransition() },
            popEnterTransition = { screenPopEnterTransition() },
            popExitTransition = { screenPopExitTransition() }
        ) {
            AskNotesScreen(
                onNavigateBack = { navController.popBackStack() },
                onSourceClick = { noteId, snippet ->
                    navController.navigate(Screen.Editor.createRoute(noteId, snippet))
                }
            )
        }

        composable(
            route = Screen.AgentJobs.route,
            enterTransition = { screenEnterTransition() },
            exitTransition = { screenExitTransition() },
            popEnterTransition = { screenPopEnterTransition() },
            popExitTransition = { screenPopExitTransition() }
        ) {
            AgentJobsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.PrivacyDashboard.route,
            enterTransition = { screenEnterTransition() },
            exitTransition = { screenExitTransition() },
            popEnterTransition = { screenPopEnterTransition() },
            popExitTransition = { screenPopExitTransition() }
        ) {
            PrivacyDashboardScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}

