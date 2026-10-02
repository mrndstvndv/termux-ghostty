package com.mrndtvndv.term.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrndtvndv.term.data.displayName
import com.mrndtvndv.term.ui.notification.NotificationState
import com.mrndtvndv.term.ui.workspace.ContentResolverWallpaperGrantStore
import com.mrndtvndv.term.ui.workspace.WallpaperController
import com.mrndtvndv.term.ui.workspace.WallpaperSelectionCoordinator
import com.mrndtvndv.term.ui.workspace.WallpaperSelectionOutcome
import com.mrndtvndv.term.ui.workspace.decodeTerminalWallpaper
import com.mrndtvndv.term.ui.workspace.retainedPredecodedWallpaper
import com.termux.terminal.compose.TerminalWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    wallpaperController: WallpaperController,
    notificationState: NotificationState,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let(viewModel::importFont)
    }
    val wallpaperPicker = rememberWallpaperPicker(viewModel, wallpaperController, notificationState)

    SettingsScreen(
        settings = settings,
        onUpdate = viewModel::update,
        onSelectFont = { pickFont.launch("*/*") },
        onClearFont = viewModel::clearFont,
        onSelectWallpaper = wallpaperPicker.select,
        onClearWallpaper = wallpaperPicker.clear,
        onBack = onBack,
    )
}

private class WallpaperPicker(
    val select: () -> Unit,
    val clear: () -> Unit,
)

@Composable
private fun rememberWallpaperPicker(
    viewModel: SettingsViewModel,
    wallpaperController: WallpaperController,
    notificationState: NotificationState,
): WallpaperPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coordinator = remember(context) {
        WallpaperSelectionCoordinator<TerminalWallpaper>(ContentResolverWallpaperGrantStore(context.contentResolver))
    }
    val idGenerator = remember { AtomicLong(System.currentTimeMillis()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val newUri = uri.toString()
        val newId = idGenerator.incrementAndGet()
        val newName = context.displayName(uri) ?: uri.lastPathSegment
        scope.launch {
            val outcome = coordinator.select(
                previousUri = viewModel.settings.value.wallpaperUri,
                newUri = newUri,
                decode = { withContext(Dispatchers.IO) { decodeTerminalWallpaper(context, newUri, newId) } },
                commit = { wallpaper ->
                    wallpaperController.predecoded = retainedPredecodedWallpaper(
                        wallpaper,
                        enabled = viewModel.settings.value.wallpaperEnabled,
                    )
                    viewModel.update { it.copy(wallpaperUri = newUri, wallpaperName = newName, wallpaperId = newId) }
                },
            )
            when (outcome) {
                WallpaperSelectionOutcome.GrantDenied -> notificationState.post(
                    title = "Wallpaper",
                    body = "Couldn't access that image. The current wallpaper was kept.",
                )
                WallpaperSelectionOutcome.ImageUnreadable -> notificationState.post(
                    title = "Wallpaper",
                    body = "That image couldn't be read. The current wallpaper was kept.",
                )
                WallpaperSelectionOutcome.Committed,
                WallpaperSelectionOutcome.Superseded -> Unit
            }
        }
    }
    return remember(launcher, coordinator, viewModel, wallpaperController) {
        WallpaperPicker(
            select = { launcher.launch(arrayOf("image/*")) },
            clear = {
                wallpaperController.predecoded = null
                coordinator.clear(viewModel.settings.value.wallpaperUri)
                viewModel.update { it.copy(wallpaperUri = null, wallpaperName = null, wallpaperId = 0L) }
            },
        )
    }
}
