package com.omnidocs.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.omnidocs.app.ui.screens.auth.AuthScreen
import com.omnidocs.app.ui.screens.editor.EditorScreen
import com.omnidocs.app.ui.screens.feed.FeedScreen
import com.omnidocs.app.ui.screens.home.HomeScreen
import com.omnidocs.app.ui.screens.ocr.OcrScreen
import com.omnidocs.app.ui.screens.settings.SettingsScreen
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
                }
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
            enterTransition = { screenEnterTransition() },
            exitTransition = { screenExitTransition() },
            popEnterTransition = { screenPopEnterTransition() },
            popExitTransition = { screenPopExitTransition() }
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

