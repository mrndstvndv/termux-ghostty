package com.mrndtvndv.term.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec

val LocalAnimationsEnabled = staticCompositionLocalOf { true }

@Composable
fun <T : Any> AppNavDisplay(
    backStack: List<T>,
    onBack: () -> Unit,
    entryProvider: (T) -> NavEntry<T>,
    entryDecorators: List<NavEntryDecorator<T>> = listOf(rememberSaveableStateHolderNavEntryDecorator()),
) {
    val animate = LocalAnimationsEnabled.current
    NavDisplay(
        backStack = backStack,
        onBack = onBack,
        entryDecorators = entryDecorators,
        transitionSpec = if (animate) defaultTransitionSpec() else noTransition(),
        popTransitionSpec = if (animate) defaultPopTransitionSpec() else noTransition(),
        predictivePopTransitionSpec = if (animate) defaultPredictivePopTransitionSpec() else { _ -> noTransform() },
        entryProvider = entryProvider,
    )
}

private fun <T : Any> noTransition(): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform =
    { noTransform() }

private fun noTransform(): ContentTransform = EnterTransition.None togetherWith ExitTransition.None
