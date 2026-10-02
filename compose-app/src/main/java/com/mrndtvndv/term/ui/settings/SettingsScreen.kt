package com.mrndtvndv.term.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.mrndtvndv.term.NativeLogcatLogger
import com.mrndtvndv.term.data.prefs.AppSettings
import com.mrndtvndv.term.ui.keyboard.validateExtraKeysJson
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.mrndtvndv.term.ui.keyboard.ExtraKeysToolbar
import com.mrndtvndv.term.ui.workspace.CursorTrailEffect
import com.mrndtvndv.term.ui.workspace.VisualEffectFrameRate
import com.mrndtvndv.term.ui.workspace.label
import com.termux.terminal.compose.WallpaperScaling


@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onSelectFont: () -> Unit,
    onClearFont: () -> Unit,
    onSelectWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Extra Keys Configuration
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "EXTRA KEYS CONFIG",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Enable Extra Keys Toolbar", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = settings.extraKeysEnabled,
                            onCheckedChange = { enabled -> onUpdate { it.copy(extraKeysEnabled = enabled) } }
                        )
                    }

                    if (settings.extraKeysEnabled) {
                        Text(
                            text = "Toolbar Preset Layout",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.Start)
                        )

                        val presets = listOf("Double Row", "Tmux", "Single Row", "Arrows Only", "Custom")
                        var expanded by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(settings.extraKeysPreset)
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier.fillMaxWidth(0.9f)
                            ) {
                                presets.forEach { presetName ->
                                    DropdownMenuItem(
                                        text = { Text(presetName) },
                                        onClick = {
                                            onUpdate { it.copy(extraKeysPreset = presetName) }
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }

                        if (settings.extraKeysPreset == "Custom") {
                            var jsonError by remember(settings.extraKeysCustomJson) {
                                mutableStateOf(validateExtraKeysJson(settings.extraKeysCustomJson))
                            }

                            OutlinedTextField(
                                value = settings.extraKeysCustomJson,
                                onValueChange = { json ->
                                    onUpdate { it.copy(extraKeysCustomJson = json) }
                                    jsonError = validateExtraKeysJson(json)
                                },
                                label = { Text("Custom Layout JSON") },
                                isError = jsonError != null,
                                supportingText = {
                                    if (jsonError != null) {
                                        Text(jsonError!!, color = MaterialTheme.colorScheme.error)
                                    } else {
                                        Text(
                                            "Examples:\n" +
                                            "• Simple key: 'ESC'\n" +
                                            "• Keyboard toggle: 'KEYBOARD'\n" +
                                            "• Popup: {key: '-', popup: '|'}\n" +
                                            "• Macro: 'CTRL b n' or {macro: 'CTRL b n', display: 'tmux →'}"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Interactive Live Preview",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.align(Alignment.Start)
                        )

                        val previewController = remember { ExtraKeysController() }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            ExtraKeysToolbar(
                                extraKeysController = previewController,
                                session = null,
                                extraKeysJson = settings.extraKeysJson
                            )
                        }
                    }
                }
            }

            // Terminal Font Size Configuration
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "TERMINAL SETTINGS",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )

                    val context = androidx.compose.ui.platform.LocalContext.current
                    val sizes = remember(context) {
                        com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences.getDefaultFontSizes(context)
                    }
                    val minFontSize = sizes[1]
                    val maxFontSize = sizes[2]

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Font Size (px)", style = MaterialTheme.typography.bodyLarge)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    if (settings.fontSize > minFontSize) {
                                        onUpdate { it.copy(fontSize = it.fontSize - 2) }
                                    }
                                },
                                enabled = settings.fontSize > minFontSize
                            ) {
                                Text("-", style = MaterialTheme.typography.titleLarge)
                            }

                            Text(
                                text = "${settings.fontSize}",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.width(32.dp),
                                textAlign = TextAlign.Center
                            )

                            IconButton(
                                onClick = {
                                    if (settings.fontSize < maxFontSize) {
                                        onUpdate { it.copy(fontSize = it.fontSize + 2) }
                                    }
                                },
                                enabled = settings.fontSize < maxFontSize
                            ) {
                                Text("+", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Keyboard Resize Debounce (ms)",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (settings.keyboardResizeDebounceMs == 0) {
                                    "0 = resize immediately (no debounce)"
                                } else {
                                    "Coalesce soft-keyboard resize by N ms before reflow"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    if (settings.keyboardResizeDebounceMs > 0) {
                                        onUpdate { it.copy(keyboardResizeDebounceMs = it.keyboardResizeDebounceMs - 5) }
                                    }
                                },
                                enabled = settings.keyboardResizeDebounceMs > 0
                            ) {
                                Text("-", style = MaterialTheme.typography.titleLarge)
                            }

                            Text(
                                text = "${settings.keyboardResizeDebounceMs}",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.width(40.dp),
                                textAlign = TextAlign.Center
                            )

                            IconButton(
                                onClick = {
                                    if (settings.keyboardResizeDebounceMs < 100) {
                                        onUpdate { it.copy(keyboardResizeDebounceMs = it.keyboardResizeDebounceMs + 5) }
                                    }
                                },
                                enabled = settings.keyboardResizeDebounceMs < 100
                            ) {
                                Text("+", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Terminal Font", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = settings.customFontName ?: "Default (Monospace)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (settings.customFontName == null) {
                                Button(
                                    onClick = onSelectFont,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(36.dp),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text("Select Font", style = MaterialTheme.typography.bodyMedium)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onSelectFont,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(36.dp),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text("Change", style = MaterialTheme.typography.bodyMedium)
                                }

                                Button(
                                    onClick = onClearFont,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(36.dp),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text("Reset", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }

                    if (settings.customFontName != null) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Use Custom Font in UI", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = "Apply the custom terminal font to the entire application UI",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.useCustomFontForWholeUi,
                                onCheckedChange = { enabled -> onUpdate { it.copy(useCustomFontForWholeUi = enabled) } }
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("App Theme", style = MaterialTheme.typography.bodyLarge)
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val themes = listOf("Light", "Dark", "Black")
                            themes.forEach { themeName ->
                                val isSelected = settings.theme == themeName
                                val containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                                val contentColor = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                
                                Button(
                                    onClick = { onUpdate { it.copy(theme = themeName) } },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = containerColor,
                                        contentColor = contentColor
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(36.dp),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text(
                                        text = themeName,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))


                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Cursor Trail", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Warp, Sweep, or Tail animation behind the cursor",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        var trailExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(
                                onClick = { trailExpanded = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(
                                    text = settings.cursorTrail.label,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            DropdownMenu(
                                expanded = trailExpanded,
                                onDismissRequest = { trailExpanded = false }
                            ) {
                                CursorTrailEffect.entries.forEach { effect ->
                                    DropdownMenuItem(
                                        text = { Text(effect.label) },
                                        onClick = {
                                            onUpdate { it.copy(cursorTrail = effect) }
                                            trailExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Effects Frame Rate", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "VSync follows the display refresh rate; lower caps reduce GPU load",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        var frameRateExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(
                                onClick = { frameRateExpanded = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(
                                    text = settings.visualEffectFrameRate.label,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            DropdownMenu(
                                expanded = frameRateExpanded,
                                onDismissRequest = { frameRateExpanded = false }
                            ) {
                                VisualEffectFrameRate.entries.forEach { frameRate ->
                                    DropdownMenuItem(
                                        text = { Text(frameRate.label) },
                                        onClick = {
                                            onUpdate { it.copy(visualEffectFrameRate = frameRate) }
                                            frameRateExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Terminal Wallpaper
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "TERMINAL WALLPAPER",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable Wallpaper", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Draw an image behind default terminal backgrounds",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.wallpaperEnabled,
                            onCheckedChange = { enabled -> onUpdate { it.copy(wallpaperEnabled = enabled) } },
                            enabled = settings.wallpaperName != null
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Wallpaper Image", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = settings.wallpaperName ?: "None",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (settings.wallpaperName == null) {
                                Button(
                                    onClick = onSelectWallpaper,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(36.dp),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text("Select Image", style = MaterialTheme.typography.bodyMedium)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onSelectWallpaper,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(36.dp),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text("Change", style = MaterialTheme.typography.bodyMedium)
                                }

                                Button(
                                    onClick = onClearWallpaper,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(36.dp),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text("Remove", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Background Opacity", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${(settings.wallpaperOpacity * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        Text(
                            text = "Higher values hide more of the wallpaper behind the terminal background",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val wallpaperOpacityState = remember { SliderState(steps = 19, trackRange = 0f..1f) }
                        wallpaperOpacityState.value = settings.wallpaperOpacity.coerceIn(0f, 1f)
                        Slider(
                            state = wallpaperOpacityState,
                            onValueChange = { opacity -> onUpdate { it.copy(wallpaperOpacity = opacity) } },
                            enabled = settings.wallpaperName != null && settings.wallpaperEnabled
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Scaling", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "How the image is fitted to the terminal viewport",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        var scalingExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(
                                onClick = { scalingExpanded = true },
                                enabled = settings.wallpaperName != null && settings.wallpaperEnabled,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(
                                    text = settings.wallpaperScaling.label(),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            DropdownMenu(
                                expanded = scalingExpanded,
                                onDismissRequest = { scalingExpanded = false }
                            ) {
                                WallpaperScaling.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label()) },
                                        onClick = {
                                            onUpdate { it.copy(wallpaperScaling = option) }
                                            scalingExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Workspace Settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "WORKSPACE SETTINGS",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hide Workspace Tabs", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Hide the tab bar in the terminal workspace. " +
                                    "Swipe left/right to switch tabs.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.hideWorkspaceTabs,
                            onCheckedChange = { enabled -> onUpdate { it.copy(hideWorkspaceTabs = enabled) } }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show Keyboard Button", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Show a keyboard toggle button above the agents button " +
                                    "in the terminal workspace.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.showKeyboardFab,
                            onCheckedChange = { enabled -> onUpdate { it.copy(showKeyboardFab = enabled) } }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hide Button While Typing", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Automatically hide the keyboard button " +
                                    "while the soft keyboard is open.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.hideKeyboardFabWhileTyping,
                            onCheckedChange = { enabled -> onUpdate { it.copy(hideKeyboardFabWhileTyping = enabled) } },
                            enabled = settings.showKeyboardFab
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Herdr Agent FAB Opacity", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = "Lower the opacity so terminal content remains visible underneath",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = "${(settings.herdrAgentFabOpacity * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        val fabOpacityState = remember { SliderState(steps = 14, trackRange = 0.25f..1f) }
                        fabOpacityState.value = settings.herdrAgentFabOpacity.coerceIn(0.25f, 1f)
                        Slider(
                            state = fabOpacityState,
                            onValueChange = { opacity -> onUpdate { it.copy(herdrAgentFabOpacity = opacity) } },
                        )
                    }
                }
            }

            // Keyboard Settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "KEYBOARD SETTINGS",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Show Keyboard On Tap", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Automatically open the soft keyboard when tapping the terminal",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.autoShowKeyboardOnTap,
                            onCheckedChange = { enabled -> onUpdate { it.copy(autoShowKeyboardOnTap = enabled) } }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Remember Keyboard State", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Restore the terminal keyboard's last visibility when reopening it",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.rememberSoftKeyboardState,
                            onCheckedChange = { enabled -> onUpdate { it.copy(rememberSoftKeyboardState = enabled) } }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "Tip: add a KEYBOARD key to a Custom extra-keys layout " +
                            "(it shows as \u2328) to toggle the keyboard from the toolbar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Unconditional Keyboard On Tap", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Show soft keyboard on tap even when terminal " +
                                    "mouse tracking (e.g. tmux) is active",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.unconditionalSoftKeyboardOnTap,
                            onCheckedChange = { enabled ->
                                onUpdate { it.copy(unconditionalSoftKeyboardOnTap = enabled) }
                            },
                            enabled = settings.autoShowKeyboardOnTap
                        )
                    }
                }
            }

            // Native & Debug Logs Settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                val context = LocalContext.current
                var logSizeText by remember { mutableStateOf(NativeLogcatLogger.getLogFileSizeMb(context)) }
                var crashDetected by remember { mutableStateOf(NativeLogcatLogger.hasDetectedNativeCrash(context)) }

                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "NATIVE & DEBUG LOGS",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Debug Performance HUD", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Show live FPS, app CPU, RAM, and missed frames over the terminal workspace",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.debugHudEnabled,
                            onCheckedChange = { enabled -> onUpdate { it.copy(debugHudEnabled = enabled) } }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Native Logcat Crash Logger", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Continuously record logcat buffer in background to " +
                                    "capture random JNI, Zig, libssh, or SFTP native library crashes",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.nativeLogcatLoggingEnabled,
                            onCheckedChange = { enabled ->
                                onUpdate { it.copy(nativeLogcatLoggingEnabled = enabled) }
                                logSizeText = NativeLogcatLogger.getLogFileSizeMb(context)
                                crashDetected = NativeLogcatLogger.hasDetectedNativeCrash(context)
                            }
                        )
                    }

                    if (crashDetected) {
                        Text(
                            text = "⚠️ Native crash (SIGSEGV / SIGABRT) detected in log file!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }

                    Text(
                        text = "Log File Size: $logSizeText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val path = NativeLogcatLogger.exportLogToDownloads(context)
                                if (path != null) {
                                    Toast.makeText(context, "Log exported to $path", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Failed to export log or log is empty",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export Log")
                        }

                        OutlinedButton(
                            onClick = {
                                NativeLogcatLogger.clearLog(context)
                                logSizeText = NativeLogcatLogger.getLogFileSizeMb(context)
                                crashDetected = NativeLogcatLogger.hasDetectedNativeCrash(context)
                                Toast.makeText(context, "Native log cleared", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Clear Log")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
