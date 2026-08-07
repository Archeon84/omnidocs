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
import com.omnidocs.app.ui.screens.auth.AuthScreen
import com.omnidocs.app.ui.screens.editor.EditorScreen
import com.omnidocs.app.ui.screens.feed.FeedScreen
import com.omnidocs.app.ui.screens.home.HomeScreen
import com.omnidocs.app.ui.screens.ocr.OcrScreen
import com.omnidocs.app.ui.screens.settings.SettingsScreen
import com.omnidocs.app.ui.screens.voice.VoiceCaptureOverlay
import com.omnidocs.app.ui.theme.MotionTokens
import com.omnidocs.app.ui.theme.screenEnterTransition
import com.omnidocs.app.ui.theme.screenExitTransition
import com.omnidocs.app.ui.theme.screenPopEnterTransition
import com.omnidocs.app.ui.theme.screenPopExitTransition

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Editor : Screen("editor?noteId={noteId}") {
        fun createRoute(noteId: String? = null): String {
            return if (noteId != null) "editor?noteId=$noteId" else "editor"
        }
    }
    object Ocr : Screen("ocr")
    object Settings : Screen("settings")
    object Feed : Screen("feed")
    object Auth : Screen("auth")
    object Voice : Screen("voice")
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
            route = Screen.Editor.route,
            arguments = listOf(
                navArgument("noteId") {
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
            EditorScreen(
                noteId = noteId,
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
    }
}

