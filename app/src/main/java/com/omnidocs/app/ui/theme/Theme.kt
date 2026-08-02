package com.omnidocs.app.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class AppTheme {
    LIGHT, DARK, AMOLED, SEPIA, OCEAN, FOREST, LAVENDER,
    SYSTEM,  // follows system dark/light mode
    DYNAMIC  // Material You dynamic color (Android 12+)
}

private val THEME_KEY = stringPreferencesKey("app_theme")
private val Context.dataStore by preferencesDataStore(name = "settings")

@Composable
fun NotesAppTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dataStore = context.dataStore
    
    val currentTheme by remember {
        dataStore.data.map { preferences ->
            val themeName = preferences[THEME_KEY] ?: AppTheme.LIGHT.name
            try {
                AppTheme.valueOf(themeName)
            } catch (e: Exception) {
                AppTheme.LIGHT
            }
        }
    }.collectAsState(initial = AppTheme.LIGHT)

    val colorScheme = when (currentTheme) {
        AppTheme.LIGHT -> lightColorScheme(
            primary = LightPrimary,
            onPrimary = LightOnPrimary,
            primaryContainer = LightPrimaryContainer,
            onPrimaryContainer = LightOnPrimaryContainer,
            secondary = LightSecondary,
            onSecondary = LightOnSecondary,
            secondaryContainer = LightSecondaryContainer,
            onSecondaryContainer = LightOnSecondaryContainer,
            tertiary = LightTertiary,
            onTertiary = LightOnTertiary,
            tertiaryContainer = LightTertiaryContainer,
            onTertiaryContainer = LightOnTertiaryContainer,
            background = LightBackground,
            onBackground = LightOnBackground,
            surface = LightSurface,
            onSurface = LightOnSurface,
            surfaceVariant = LightSurfaceVariant,
            onSurfaceVariant = LightOnSurfaceVariant,
            outline = LightOutline,
            outlineVariant = LightOutlineVariant,
            error = LightError,
            onError = LightOnError,
            errorContainer = LightErrorContainer,
            onErrorContainer = LightOnErrorContainer,
            surfaceDim = LightSurfaceDim,
            surfaceBright = LightSurfaceBright,
            surfaceContainerLowest = LightSurfaceContainerLowest,
            surfaceContainerLow = LightSurfaceContainerLow,
            surfaceContainer = LightSurfaceContainer,
            surfaceContainerHigh = LightSurfaceContainerHigh,
            surfaceContainerHighest = LightSurfaceContainerHighest,
            inverseSurface = LightInverseSurface,
            inverseOnSurface = LightInverseOnSurface,
            inversePrimary = LightInversePrimary,
            scrim = LightScrim
        )
        AppTheme.DARK -> darkColorScheme(
            primary = DarkPrimary,
            onPrimary = DarkOnPrimary,
            primaryContainer = DarkPrimaryContainer,
            onPrimaryContainer = DarkOnPrimaryContainer,
            secondary = DarkSecondary,
            onSecondary = DarkOnSecondary,
            secondaryContainer = DarkSecondaryContainer,
            onSecondaryContainer = DarkOnSecondaryContainer,
            tertiary = DarkTertiary,
            onTertiary = DarkOnTertiary,
            tertiaryContainer = DarkTertiaryContainer,
            onTertiaryContainer = DarkOnTertiaryContainer,
            background = DarkBackground,
            onBackground = DarkOnBackground,
            surface = DarkSurface,
            onSurface = DarkOnSurface,
            surfaceVariant = DarkSurfaceVariant,
            onSurfaceVariant = DarkOnSurfaceVariant,
            outline = DarkOutline,
            outlineVariant = DarkOutlineVariant,
            error = DarkError,
            onError = DarkOnError,
            errorContainer = DarkErrorContainer,
            onErrorContainer = DarkOnErrorContainer,
            surfaceDim = DarkSurfaceDim,
            surfaceBright = DarkSurfaceBright,
            surfaceContainerLowest = DarkSurfaceContainerLowest,
            surfaceContainerLow = DarkSurfaceContainerLow,
            surfaceContainer = DarkSurfaceContainer,
            surfaceContainerHigh = DarkSurfaceContainerHigh,
            surfaceContainerHighest = DarkSurfaceContainerHighest,
            inverseSurface = DarkInverseSurface,
            inverseOnSurface = DarkInverseOnSurface,
            inversePrimary = DarkInversePrimary,
            scrim = DarkScrim
        )
        AppTheme.AMOLED -> darkColorScheme(
            primary = DarkPrimary,
            onPrimary = DarkOnPrimary,
            primaryContainer = DarkPrimaryContainer,
            onPrimaryContainer = DarkOnPrimaryContainer,
            secondary = DarkSecondary,
            onSecondary = DarkOnSecondary,
            secondaryContainer = DarkSecondaryContainer,
            onSecondaryContainer = DarkOnSecondaryContainer,
            tertiary = DarkTertiary,
            onTertiary = DarkOnTertiary,
            tertiaryContainer = DarkTertiaryContainer,
            onTertiaryContainer = DarkOnTertiaryContainer,
            background = AmoledBackground,
            onBackground = DarkOnBackground,
            surface = AmoledSurface,
            onSurface = DarkOnSurface,
            surfaceVariant = AmoledSurfaceVariant,
            onSurfaceVariant = DarkOnSurfaceVariant,
            outline = DarkOutline,
            outlineVariant = DarkOutlineVariant,
            error = DarkError,
            onError = DarkOnError,
            errorContainer = DarkErrorContainer,
            onErrorContainer = DarkOnErrorContainer,
            surfaceDim = AmoledSurface,
            surfaceBright = DarkSurfaceBright,
            surfaceContainerLowest = AmoledSurfaceContainerLowest,
            surfaceContainerLow = AmoledSurfaceContainerLow,
            surfaceContainer = AmoledSurfaceContainer,
            surfaceContainerHigh = AmoledSurfaceContainerHigh,
            surfaceContainerHighest = AmoledSurfaceContainerHighest,
            inverseSurface = AmoledInverseSurface,
            inverseOnSurface = AmoledInverseOnSurface,
            inversePrimary = AmoledInversePrimary,
            scrim = AmoledScrim
        )
        AppTheme.SEPIA -> lightColorScheme(
            primary = SepiaPrimary,
            onPrimary = SepiaOnPrimary,
            primaryContainer = SepiaPrimaryContainer,
            onPrimaryContainer = SepiaOnPrimaryContainer,
            secondary = SepiaSecondary,
            onSecondary = SepiaOnSecondary,
            secondaryContainer = SepiaSecondaryContainer,
            onSecondaryContainer = SepiaOnSecondaryContainer,
            tertiary = SepiaTertiary,
            onTertiary = SepiaOnTertiary,
            tertiaryContainer = SepiaTertiaryContainer,
            onTertiaryContainer = SepiaOnTertiaryContainer,
            background = SepiaBackground,
            onBackground = SepiaOnBackground,
            surface = SepiaSurface,
            onSurface = SepiaOnSurface,
            surfaceVariant = SepiaSurfaceVariant,
            onSurfaceVariant = SepiaOnSurfaceVariant,
            outline = SepiaOutline,
            outlineVariant = SepiaOutlineVariant,
            error = SepiaError,
            onError = SepiaOnError,
            errorContainer = SepiaErrorContainer,
            onErrorContainer = SepiaOnErrorContainer,
            surfaceContainerLowest = SepiaSurfaceContainerLowest,
            surfaceContainerLow = SepiaSurfaceContainerLow,
            surfaceContainer = SepiaSurfaceContainer,
            surfaceContainerHigh = SepiaSurfaceContainerHigh,
            surfaceContainerHighest = SepiaSurfaceContainerHighest,
            inverseSurface = SepiaInverseSurface,
            inverseOnSurface = SepiaInverseOnSurface,
            inversePrimary = SepiaInversePrimary,
            scrim = SepiaScrim
        )
        AppTheme.OCEAN -> lightColorScheme(
            primary = OceanPrimary,
            onPrimary = OceanOnPrimary,
            primaryContainer = OceanPrimaryContainer,
            onPrimaryContainer = OceanOnPrimaryContainer,
            secondary = OceanSecondary,
            onSecondary = OceanOnSecondary,
            secondaryContainer = OceanSecondaryContainer,
            onSecondaryContainer = OceanOnSecondaryContainer,
            tertiary = OceanTertiary,
            onTertiary = OceanOnTertiary,
            tertiaryContainer = OceanTertiaryContainer,
            onTertiaryContainer = OceanOnTertiaryContainer,
            background = OceanBackground,
            onBackground = OceanOnBackground,
            surface = OceanSurface,
            onSurface = OceanOnSurface,
            surfaceVariant = OceanSurfaceVariant,
            onSurfaceVariant = OceanOnSurfaceVariant,
            outline = OceanOutline,
            outlineVariant = OceanOutlineVariant,
            error = OceanError,
            onError = OceanOnError,
            errorContainer = OceanErrorContainer,
            onErrorContainer = OceanOnErrorContainer,
            surfaceContainerLowest = OceanSurfaceContainerLowest,
            surfaceContainerLow = OceanSurfaceContainerLow,
            surfaceContainer = OceanSurfaceContainer,
            surfaceContainerHigh = OceanSurfaceContainerHigh,
            surfaceContainerHighest = OceanSurfaceContainerHighest,
            inverseSurface = OceanInverseSurface,
            inverseOnSurface = OceanInverseOnSurface,
            inversePrimary = OceanInversePrimary,
            scrim = OceanScrim
        )
        AppTheme.FOREST -> lightColorScheme(
            primary = ForestPrimary,
            onPrimary = ForestOnPrimary,
            primaryContainer = ForestPrimaryContainer,
            onPrimaryContainer = ForestOnPrimaryContainer,
            secondary = ForestSecondary,
            onSecondary = ForestOnSecondary,
            secondaryContainer = ForestSecondaryContainer,
            onSecondaryContainer = ForestOnSecondaryContainer,
            tertiary = ForestTertiary,
            onTertiary = ForestOnTertiary,
            tertiaryContainer = ForestTertiaryContainer,
            onTertiaryContainer = ForestOnTertiaryContainer,
            background = ForestBackground,
            onBackground = ForestOnBackground,
            surface = ForestSurface,
            onSurface = ForestOnSurface,
            surfaceVariant = ForestSurfaceVariant,
            onSurfaceVariant = ForestOnSurfaceVariant,
            outline = ForestOutline,
            outlineVariant = ForestOutlineVariant,
            error = ForestError,
            onError = ForestOnError,
            errorContainer = ForestErrorContainer,
            onErrorContainer = ForestOnErrorContainer,
            surfaceContainerLowest = ForestSurfaceContainerLowest,
            surfaceContainerLow = ForestSurfaceContainerLow,
            surfaceContainer = ForestSurfaceContainer,
            surfaceContainerHigh = ForestSurfaceContainerHigh,
            surfaceContainerHighest = ForestSurfaceContainerHighest,
            inverseSurface = ForestInverseSurface,
            inverseOnSurface = ForestInverseOnSurface,
            inversePrimary = ForestInversePrimary,
            scrim = ForestScrim
        )
        AppTheme.LAVENDER -> lightColorScheme(
            primary = LavenderPrimary,
            onPrimary = LavenderOnPrimary,
            primaryContainer = LavenderPrimaryContainer,
            onPrimaryContainer = LavenderOnPrimaryContainer,
            secondary = LavenderSecondary,
            onSecondary = LavenderOnSecondary,
            secondaryContainer = LavenderSecondaryContainer,
            onSecondaryContainer = LavenderOnSecondaryContainer,
            tertiary = LavenderTertiary,
            onTertiary = LavenderOnTertiary,
            tertiaryContainer = LavenderTertiaryContainer,
            onTertiaryContainer = LavenderOnTertiaryContainer,
            background = LavenderBackground,
            onBackground = LavenderOnBackground,
            surface = LavenderSurface,
            onSurface = LavenderOnSurface,
            surfaceVariant = LavenderSurfaceVariant,
            onSurfaceVariant = LavenderOnSurfaceVariant,
            outline = LavenderOutline,
            outlineVariant = LavenderOutlineVariant,
            error = LavenderError,
            onError = LavenderOnError,
            errorContainer = LavenderErrorContainer,
            onErrorContainer = LavenderOnErrorContainer,
            surfaceContainerLowest = LavenderSurfaceContainerLowest,
            surfaceContainerLow = LavenderSurfaceContainerLow,
            surfaceContainer = LavenderSurfaceContainer,
            surfaceContainerHigh = LavenderSurfaceContainerHigh,
            surfaceContainerHighest = LavenderSurfaceContainerHighest,
            inverseSurface = LavenderInverseSurface,
            inverseOnSurface = LavenderInverseOnSurface,
            inversePrimary = LavenderInversePrimary,
            scrim = LavenderScrim
        )
        AppTheme.SYSTEM -> {
            if (isSystemInDarkTheme()) darkColorScheme(
                primary = DarkPrimary,
                onPrimary = DarkOnPrimary,
                primaryContainer = DarkPrimaryContainer,
                onPrimaryContainer = DarkOnPrimaryContainer,
                secondary = DarkSecondary,
                onSecondary = DarkOnSecondary,
                secondaryContainer = DarkSecondaryContainer,
                onSecondaryContainer = DarkOnSecondaryContainer,
                tertiary = DarkTertiary,
                onTertiary = DarkOnTertiary,
                tertiaryContainer = DarkTertiaryContainer,
                onTertiaryContainer = DarkOnTertiaryContainer,
                background = DarkBackground,
                onBackground = DarkOnBackground,
                surface = DarkSurface,
                onSurface = DarkOnSurface,
                surfaceVariant = DarkSurfaceVariant,
                onSurfaceVariant = DarkOnSurfaceVariant,
                outline = DarkOutline,
                outlineVariant = DarkOutlineVariant,
                error = DarkError,
                onError = DarkOnError,
                errorContainer = DarkErrorContainer,
                onErrorContainer = DarkOnErrorContainer,
                surfaceDim = DarkSurfaceDim,
                surfaceBright = DarkSurfaceBright,
                surfaceContainerLowest = DarkSurfaceContainerLowest,
                surfaceContainerLow = DarkSurfaceContainerLow,
                surfaceContainer = DarkSurfaceContainer,
                surfaceContainerHigh = DarkSurfaceContainerHigh,
                surfaceContainerHighest = DarkSurfaceContainerHighest,
                inverseSurface = DarkInverseSurface,
                inverseOnSurface = DarkInverseOnSurface,
                inversePrimary = DarkInversePrimary,
                scrim = DarkScrim
            ) else lightColorScheme(
                primary = LightPrimary,
                onPrimary = LightOnPrimary,
                primaryContainer = LightPrimaryContainer,
                onPrimaryContainer = LightOnPrimaryContainer,
                secondary = LightSecondary,
                onSecondary = LightOnSecondary,
                secondaryContainer = LightSecondaryContainer,
                onSecondaryContainer = LightOnSecondaryContainer,
                tertiary = LightTertiary,
                onTertiary = LightOnTertiary,
                tertiaryContainer = LightTertiaryContainer,
                onTertiaryContainer = LightOnTertiaryContainer,
                background = LightBackground,
                onBackground = LightOnBackground,
                surface = LightSurface,
                onSurface = LightOnSurface,
                surfaceVariant = LightSurfaceVariant,
                onSurfaceVariant = LightOnSurfaceVariant,
                outline = LightOutline,
                outlineVariant = LightOutlineVariant,
                error = LightError,
                onError = LightOnError,
                errorContainer = LightErrorContainer,
                onErrorContainer = LightOnErrorContainer,
                surfaceDim = LightSurfaceDim,
                surfaceBright = LightSurfaceBright,
                surfaceContainerLowest = LightSurfaceContainerLowest,
                surfaceContainerLow = LightSurfaceContainerLow,
                surfaceContainer = LightSurfaceContainer,
                surfaceContainerHigh = LightSurfaceContainerHigh,
                surfaceContainerHighest = LightSurfaceContainerHighest,
                inverseSurface = LightInverseSurface,
                inverseOnSurface = LightInverseOnSurface,
                inversePrimary = LightInversePrimary,
                scrim = LightScrim
            )
        }
        AppTheme.DYNAMIC -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
                else dynamicLightColorScheme(context)
            } else {
                // Fallback to custom LIGHT palette on older devices
                lightColorScheme(
                    primary = LightPrimary,
                    onPrimary = LightOnPrimary,
                    primaryContainer = LightPrimaryContainer,
                    onPrimaryContainer = LightOnPrimaryContainer,
                    secondary = LightSecondary,
                    onSecondary = LightOnSecondary,
                    secondaryContainer = LightSecondaryContainer,
                    onSecondaryContainer = LightOnSecondaryContainer,
                    tertiary = LightTertiary,
                    onTertiary = LightOnTertiary,
                    tertiaryContainer = LightTertiaryContainer,
                    onTertiaryContainer = LightOnTertiaryContainer,
                    background = LightBackground,
                    onBackground = LightOnBackground,
                    surface = LightSurface,
                    onSurface = LightOnSurface,
                    surfaceVariant = LightSurfaceVariant,
                    onSurfaceVariant = LightOnSurfaceVariant,
                    outline = LightOutline,
                    outlineVariant = LightOutlineVariant,
                    error = LightError,
                    onError = LightOnError,
                    errorContainer = LightErrorContainer,
                    onErrorContainer = LightOnErrorContainer,
                    surfaceDim = LightSurfaceDim,
                    surfaceBright = LightSurfaceBright,
                    surfaceContainerLowest = LightSurfaceContainerLowest,
                    surfaceContainerLow = LightSurfaceContainerLow,
                    surfaceContainer = LightSurfaceContainer,
                    surfaceContainerHigh = LightSurfaceContainerHigh,
                    surfaceContainerHighest = LightSurfaceContainerHighest,
                    inverseSurface = LightInverseSurface,
                    inverseOnSurface = LightInverseOnSurface,
                    inversePrimary = LightInversePrimary,
                    scrim = LightScrim
                )
            }
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Let edge-to-edge handle status bar transparency — do not paint it.
            // Only control light/dark icon appearance for contrast.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                currentTheme != AppTheme.DARK && currentTheme != AppTheme.AMOLED
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes
    ) {
        Crossfade(
            targetState = currentTheme,
            animationSpec = tween(durationMillis = 300),
            label = "themeTransition"
        ) {
            content()
        }
    }
}

@Composable
fun rememberThemeManager(): ThemeManager {
    val context = LocalContext.current
    return remember { ThemeManager(context) }
}

class ThemeManager(private val context: Context) {
    private val _currentTheme = MutableStateFlow(AppTheme.LIGHT)
    val currentTheme: StateFlow<AppTheme> = _currentTheme.asStateFlow()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            context.dataStore.data.collect { preferences ->
                val themeName = preferences[THEME_KEY] ?: AppTheme.LIGHT.name
                _currentTheme.value = try {
                    AppTheme.valueOf(themeName)
                } catch (e: Exception) {
                    AppTheme.LIGHT
                }
            }
        }
    }

    fun setTheme(theme: AppTheme) {
        _currentTheme.value = theme
        CoroutineScope(Dispatchers.IO).launch {
            context.dataStore.edit { preferences ->
                preferences[THEME_KEY] = theme.name
            }
        }
    }
}

