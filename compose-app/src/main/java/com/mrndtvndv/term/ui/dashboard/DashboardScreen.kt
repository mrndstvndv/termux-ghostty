package com.mrndtvndv.term.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

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

@Suppress("LongParameterList", "LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    isLoading: Boolean,
    errorMessage: String?,
    onConnect: (String, Int, String, String) -> Unit,
    onSettingsClick: () -> Unit,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ghostty SSH") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // SSH Connection Form
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
            }
        }
    }
}
