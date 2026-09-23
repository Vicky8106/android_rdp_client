package com.freerdp.client.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.freerdp.client.di.AppContainer
import com.freerdp.client.ui.components.LoadingState
import com.freerdp.client.ui.components.touchTarget
import com.freerdp.client.ui.components.PresetDescription
import com.freerdp.core.engine.PerformancePreset
import com.freerdp.feature.session.CredentialStorageType

/**
 * Create/edit profile form: validated host/port/domain, Keystore-backed password entry
 * (via CharArray), performance preset picker and delete-with-confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    container: AppContainer,
    profileId: String?,
    onDone: () -> Unit
) {
    val viewModel: ProfileEditorViewModel = viewModel(
        key = "profile_editor_${profileId ?: "new"}",
        factory = viewModelFactory {
            initializer {
                ProfileEditorViewModel(
                    profileRepository = container.profileRepository,
                    credentialStore = container.credentialStore,
                    profileId = profileId
                )
            }
        }
    )
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onDone()
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    if (state.loading) {
        LoadingState("Loading profile…")
        return
    }

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New profile" else "Edit profile") },
                navigationIcon = {
                    IconButton(onClick = onDone, modifier = Modifier.touchTarget()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(
                            onClick = viewModel::requestDelete,
                            modifier = Modifier.touchTarget()
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete profile",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::save,
                    enabled = !state.saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .touchTarget()
                ) {
                    Text(if (state.saving) "Saving…" else if (state.isNew) "Create profile" else "Save changes")
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.label,
                onValueChange = viewModel::onLabel,
                label = { Text("Profile name *") },
                isError = state.errors.label != null,
                supportingText = { state.errors.label?.let { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.hostname,
                onValueChange = viewModel::onHostname,
                label = { Text("Host name or IP *") },
                isError = state.errors.hostname != null,
                supportingText = {
                    state.errors.hostname?.let { Text(it) }
                        ?: Text("e.g. workstation.corp.example or 10.0.0.5")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.port,
                onValueChange = viewModel::onPort,
                label = { Text("Port *") },
                isError = state.errors.port != null,
                supportingText = { state.errors.port?.let { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsername,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.domain,
                onValueChange = viewModel::onDomain,
                label = { Text("Domain (optional)") },
                isError = state.errors.domain != null,
                supportingText = { state.errors.domain?.let { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPassword,
                label = { Text("Password") },
                supportingText = {
                    Text(
                        if (state.isNew) "Stored encrypted in the Android Keystore vault"
                        else "Leave blank to keep the saved password"
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Save password on this device", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Off: you'll be asked for the password at every connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.credentialStorageType == CredentialStorageType.KEYSTORE_ENCRYPTED,
                    onCheckedChange = { checked ->
                        viewModel.onCredentialStorageChanged(
                            if (checked) CredentialStorageType.KEYSTORE_ENCRYPTED
                            else CredentialStorageType.PROMPT_ON_CONNECT
                        )
                    },
                    modifier = Modifier.touchTarget()
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Network Level Authentication", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "CredSSP sign-in before the desktop loads (recommended)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.nlaEnabled,
                    onCheckedChange = viewModel::onNlaChanged,
                    modifier = Modifier.touchTarget()
                )
            }

            HorizontalDivider()
            Text("Performance preset", style = MaterialTheme.typography.titleMedium)
            PerformancePreset.entries.forEach { preset ->
                PresetRow(
                    preset = preset,
                    selected = state.performancePreset == preset,
                    onSelect = { viewModel.onPreset(preset) }
                )
            }
            HorizontalDivider(Modifier.padding(bottom = 16.dp))
        }
    }

    if (state.deleteConfirmVisible) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Delete this profile?") },
            text = { Text("Connection details and the stored password will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete, modifier = Modifier.touchTarget()) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete, modifier = Modifier.touchTarget()) {
                    Text("Cancel")
                }
            }
        )
    }
}

/** Radio row with name + derived description; shared with the Settings preset picker. */
@Composable
fun PresetRow(
    preset: PerformancePreset,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .touchTarget(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 4.dp)) {
            Text(presetDisplayName(preset), style = MaterialTheme.typography.bodyLarge)
            Text(
                PresetDescription.forPreset(preset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun presetDisplayName(preset: PerformancePreset): String = when (preset) {
    PerformancePreset.ULTRA_LOW_LATENCY -> "Ultra-Low Latency"
    PerformancePreset.LOW_LATENCY -> "Low Latency"
    PerformancePreset.BALANCED -> "Balanced Mobile"
    PerformancePreset.DATA_SAVER -> "Data Saver"
    PerformancePreset.BATTERY_SAVER -> "Battery Saver"
}
