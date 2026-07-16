package com.mrndtvndv.term.ui.dashboard

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.ui.keyboard.ExtraKeysToolbar
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController

const val PRESET_DOUBLE_ROW = "[['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]"
const val PRESET_SINGLE_ROW = "[[ESC, TAB, CTRL, ALT, {key: '-', popup: '|'}, DOWN, UP]]"
const val PRESET_ARROWS_ONLY = "[[ESC, TAB, CTRL, ALT, UP, LEFT, DOWN, RIGHT]]"

fun validateExtraKeysJson(json: String): String? {
    if (json.isBlank()) return "JSON layout cannot be empty"
    return try {
        val outer = org.json.JSONArray(json)
        for (i in 0 until outer.length()) {
            val inner = outer.getJSONArray(i)
            for (j in 0 until inner.length()) {
                val element = inner.get(j)
                if (element !is String && element !is org.json.JSONObject) {
                    return "Element at [$i][$j] must be a string or object"
                }
                if (element is org.json.JSONObject) {
                    if (!element.has("key") && !element.has("macro")) {
                        return "Object at [$i][$j] must specify 'key' or 'macro'"
                    }
                    if (element.has("key") && element.has("macro")) {
                        return "Object at [$i][$j] cannot specify both 'key' and 'macro'"
                    }
                }
            }
        }
        null // Valid JSON structure
    } catch (e: Exception) {
        "Invalid JSON format: ${e.localizedMessage}"
    }
}

@Composable
fun DashboardScreen(
    isLoading: Boolean,
    errorMessage: String?,
    onConnect: (String, Int, String, String) -> Unit,
    extraKeysEnabled: Boolean,
    onExtraKeysEnabledChange: (Boolean) -> Unit,
    extraKeysPreset: String,
    onExtraKeysPresetChange: (String) -> Unit,
    extraKeysCustomJson: String,
    onExtraKeysCustomJsonChange: (String) -> Unit,
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    appTheme: String,
    onThemeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialHost: String = "10.0.2.2",
    initialPort: Int = 2222,
    initialUsername: String = "root",
    initialPassword: String = ""
) {
    var host by remember(initialHost) { mutableStateOf(initialHost) }
    var portString by remember(initialPort) { mutableStateOf(initialPort.toString()) }
    var username by remember(initialUsername) { mutableStateOf(initialUsername) }
    var password by remember(initialPassword) { mutableStateOf(initialPassword) }

    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 12.dp, horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card 1: SSH Connection Form
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
                        text = "SSH CONNECTION",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host") },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = portString,
                        onValueChange = { portString = it },
                        label = { Text("Port") },
                        singleLine = true,
                        enabled = !isLoading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !isLoading,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val port = portString.toIntOrNull() ?: 22
                            onConnect(host, port, username, password)
                        },
                        enabled = !isLoading && host.isNotBlank() && username.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Text("Connect")
                        }
                    }
                }
            }

            // Card 2: Extra Keys Configuration
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
                            checked = extraKeysEnabled,
                            onCheckedChange = onExtraKeysEnabledChange,
                            enabled = !isLoading
                        )
                    }

                    if (extraKeysEnabled) {
                        Text(
                            text = "Toolbar Preset Layout",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.Start)
                        )

                        val presets = listOf("Double Row", "Single Row", "Arrows Only", "Custom")
                        var expanded by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading
                            ) {
                                Text(extraKeysPreset)
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
                                            onExtraKeysPresetChange(presetName)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }

                        val resolvedJson = when (extraKeysPreset) {
                            "Double Row" -> PRESET_DOUBLE_ROW
                            "Single Row" -> PRESET_SINGLE_ROW
                            "Arrows Only" -> PRESET_ARROWS_ONLY
                            else -> extraKeysCustomJson
                        }

                        if (extraKeysPreset == "Custom") {
                            var jsonError by remember(extraKeysCustomJson) {
                                mutableStateOf(validateExtraKeysJson(extraKeysCustomJson))
                            }

                            OutlinedTextField(
                                value = extraKeysCustomJson,
                                onValueChange = {
                                    onExtraKeysCustomJsonChange(it)
                                    jsonError = validateExtraKeysJson(it)
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
                                            "• Popup: {key: '-', popup: '|'}\n" +
                                            "• Macro: 'CTRL b n' or {macro: 'CTRL b n', display: 'tmux →'}"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading,
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
                                getActiveTerminalView = { null },
                                session = null,
                                extraKeysJson = resolvedJson
                            )
                        }
                    }
                }
            }

            // Card 3: Terminal Font Size Configuration
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
                                    if (fontSize > minFontSize) {
                                        onFontSizeChange(fontSize - 2)
                                    }
                                },
                                enabled = !isLoading && fontSize > minFontSize
                            ) {
                                Text("-", style = MaterialTheme.typography.titleLarge)
                            }

                            Text(
                                text = "$fontSize",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.width(32.dp),
                                textAlign = TextAlign.Center
                            )

                            IconButton(
                                onClick = {
                                    if (fontSize < maxFontSize) {
                                        onFontSizeChange(fontSize + 2)
                                    }
                                },
                                enabled = !isLoading && fontSize < maxFontSize
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
                        Text("App Theme", style = MaterialTheme.typography.bodyLarge)
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val themes = listOf("Light", "Dark", "Black")
                            themes.forEach { themeName ->
                                val isSelected = appTheme == themeName
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
                                    onClick = { onThemeChange(themeName) },
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
                }
            }
        }
    }
}
