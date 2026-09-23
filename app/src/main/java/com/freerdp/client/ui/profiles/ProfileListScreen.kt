package com.freerdp.client.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.text.format.DateUtils
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.freerdp.client.di.AppContainer
import com.freerdp.client.ui.components.EmptyState
import com.freerdp.client.ui.components.LoadingState
import com.freerdp.client.ui.components.touchTarget
import com.freerdp.feature.session.RdpProfile

/**
 * Material 3 profile manager: one-tap connect, empty state, delete confirmation and
 * error surfacing with snackbar feedback.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    container: AppContainer,
    onConnect: (RdpProfile) -> Unit,
    onEditProfile: (String?) -> Unit,
    onOpenSettings: () -> Unit
) {
    val viewModel: ProfilesViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ProfilesViewModel(container.profileRepository, container.credentialStore)
            }
        }
    )
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Surface repository errors both as a banner (persistent) and a snackbar (transient).
    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote Desktops") },
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .touchTarget()
                            .padding(4.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEditProfile(null) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New profile") }
            )
        }
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when {
            state.isLoading -> LoadingState("Loading profiles…", contentModifier)

            state.error != null && state.profiles.isEmpty() -> EmptyState(
                icon = Icons.Default.Computer,
                title = "Couldn't load your profiles",
                subtitle = state.error ?: "",
                actionLabel = "Create a profile",
                onAction = { onEditProfile(null) },
                modifier = contentModifier
            )

            state.profiles.isEmpty() -> EmptyState(
                icon = Icons.Default.Computer,
                title = "No remote desktops yet",
                subtitle = "Create a profile for your work PC or server, then connect with a single tap.",
                actionLabel = "Create your first profile",
                onAction = { onEditProfile(null) },
                modifier = contentModifier
            )

            else -> LazyColumn(
                modifier = contentModifier,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        onConnect = { onConnect(profile) },
                        onEdit = { onEditProfile(profile.id) },
                        onDelete = { viewModel.requestDelete(profile) }
                    )
                }
            }
        }

        state.deleteCandidate?.let { candidate ->
            AlertDialog(
                onDismissRequest = viewModel::cancelDelete,
                title = { Text("Delete \"${candidate.label}\"?") },
                text = {
                    Text(
                        "The connection details for ${candidate.hostname}:${candidate.port} and its " +
                            "stored password will be permanently removed."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = viewModel::confirmDelete,
                        modifier = Modifier.touchTarget()
                    ) {
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
}

@Composable
private fun ProfileCard(
    profile: RdpProfile,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        profile.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${profile.hostname}:${profile.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val userLine = buildString {
                        if (profile.username.isNotBlank()) append(profile.username)
                        if (profile.domain.isNotBlank()) {
                            if (isNotEmpty()) append("@")
                            append(profile.domain)
                        }
                    }
                    if (userLine.isNotBlank()) {
                        Text(
                            userLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    profile.lastConnectedTimestamp?.let { ts ->
                        Text(
                            "Last connected ${DateUtils.getRelativeTimeSpanString(ts)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    IconButton(onClick = onEdit, modifier = Modifier.touchTarget()) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit ${profile.label}")
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.touchTarget()) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete ${profile.label}",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("Connect", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
