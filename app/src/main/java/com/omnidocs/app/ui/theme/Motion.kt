package com.omnidocs.app.ui.theme

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Shared motion tokens — single source of truth for all screen transitions. */
object MotionTokens {
    const val DURATION_MS = 400
    const val STAGGER_MS = 50L
    const val SLIDE_FRACTION = 3 // slide by 1/3 of screen width

    // Spring presets
    val ListEntrance = spring<Float>(dampingRatio = 0.7f, stiffness = 300f)
    val ScreenTransition = spring<Float>(dampingRatio = 0.85f, stiffness = 200f)
    val ButtonPress = spring<Float>(dampingRatio = 0.6f, stiffness = 500f)
    val CardPress = spring<Float>(dampingRatio = 0.7f, stiffness = 400f)
}

/**
 * Check if the device has reduced motion enabled via accessibility settings.
 * Callable from both composable and non-composable contexts.
 */
fun isReducedMotionEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    if (am?.isEnabled == true && am.isTouchExplorationEnabled) return true
    return try {
        Settings.Secure.getInt(context.contentResolver, "transition_animation_scale", 1) == 0
    } catch (_: Exception) { false }
}

/** Composable convenience wrapper for [isReducedMotionEnabled]. */
@Composable
fun isReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) { isReducedMotionEnabled(context) }
}

/**
 * Standard screen transition: fade + slide from right.
 * Pop transitions slide from left (reversed direction).
 */
fun screenEnterTransition(): EnterTransition =
    fadeIn(animationSpec = tween(MotionTokens.DURATION_MS)) +
        slideInHorizontally(animationSpec = tween(MotionTokens.DURATION_MS)) { it / MotionTokens.SLIDE_FRACTION }

fun screenExitTransition(): ExitTransition =
    fadeOut(animationSpec = tween(MotionTokens.DURATION_MS)) +
        slideOutHorizontally(animationSpec = tween(MotionTokens.DURATION_MS)) { it / MotionTokens.SLIDE_FRACTION }

fun screenPopEnterTransition(): EnterTransition =
    fadeIn(animationSpec = tween(MotionTokens.DURATION_MS)) +
        slideInHorizontally(animationSpec = tween(MotionTokens.DURATION_MS)) { -it / MotionTokens.SLIDE_FRACTION }

fun screenPopExitTransition(): ExitTransition =
    fadeOut(animationSpec = tween(MotionTokens.DURATION_MS)) +
        slideOutHorizontally(animationSpec = tween(MotionTokens.DURATION_MS)) { -it / MotionTokens.SLIDE_FRACTION }
