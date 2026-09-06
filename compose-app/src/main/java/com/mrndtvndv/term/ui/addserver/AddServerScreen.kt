package com.mrndtvndv.term.ui.addserver

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.domain.AuthType
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.ui.components.ImagePasteSettingsSection
import com.mrndtvndv.term.ui.components.rememberImagePasteSettingsState

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    onSave: (ServerConfig) -> Unit,
    onBack: () -> Unit,
    initialConfig: ServerConfig? = null,
    modifier: Modifier = Modifier,
) {
    val initialPassword = (initialConfig?.auth as? AuthType.Password)?.password.orEmpty()
    var label by remember(initialConfig) { mutableStateOf(initialConfig?.label ?: "") }
    var host by remember(initialConfig) { mutableStateOf(initialConfig?.host ?: "") }
    var portString by remember(initialConfig) { mutableStateOf(initialConfig?.port?.toString() ?: "22") }
    var username by remember(initialConfig) { mutableStateOf(initialConfig?.username ?: "") }
    var password by remember(initialConfig) { mutableStateOf(initialPassword) }
    var herdrEnabled by remember(initialConfig) { mutableStateOf(initialConfig?.herdrEnabled ?: true) }
    val imagePasteState = rememberImagePasteSettingsState(initialConfig)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initialConfig == null) "Add Server" else "Edit Server") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = portString,
                onValueChange = { portString = it },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Herdr Integration", modifier = Modifier.weight(1f))
                Switch(checked = herdrEnabled, onCheckedChange = { herdrEnabled = it })
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            ImagePasteSettingsSection(
                state = imagePasteState,
                directorySupportingText =
                    "Remote directory where pasted images are stored. Required for image pasting.",
                activeSupportingText = "Upload and paste clipboard images into terminal",
            )

            Button(
                onClick = {
                    onSave(
                        ServerConfig(
                            id = initialConfig?.id ?: java.util.UUID.randomUUID().toString(),
                            label = label,
                            host = host,
                            port = parsePort(portString),
                            username = username,
                            auth = AuthType.Password(password),
                            herdrEnabled = herdrEnabled,
                            imagePasteEnabled = imagePasteState.isPasteActive,
                            imagePasteDirectory = imagePasteState.trimmedDirectory,
                            imagePasteAutoCleanup = imagePasteState.autoCleanup,
                            imagePasteMaxFiles = imagePasteState.maxFiles,
                        )
                    )
                },
                enabled = host.isNotBlank() && username.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}

private fun parsePort(text: String): Int = text.toIntOrNull() ?: 22
