package com.freerdp.client.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.freerdp.client.di.AppContainer
import com.freerdp.client.settings.ThemeMode
import com.freerdp.client.ui.components.touchTarget
import com.freerdp.client.ui.editor.PresetRow
import com.freerdp.core.engine.PerformancePreset

/**
 * Settings: theme, telemetry HUD default, touchpad default, performance preset picker
 * (Ultra-Low Latency / Balanced Mobile / Data Saver / Battery Saver), demo engine
 * switch and the trust-on-first-use certificate list with revocation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val viewModel: SettingsViewModel = remember {
        SettingsViewModel(container.settings, container.appContext, container.presetSwitcher)
    }
    val settings by viewModel.appSettings.collectAsState()
    var recommendation by remember { mutableStateOf<PerformancePreset?>(null) }
    // Compute the live recommendation once on entry (network + battery aware adapter).
    LaunchedEffect(Unit) { recommendation = viewModel.recommendedPreset() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.touchTarget()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle("Appearance")
            ChoiceRow("Follow system", settings.themeMode == ThemeMode.SYSTEM) { viewModel.setThemeMode(ThemeMode.SYSTEM) }
            ChoiceRow("Light", settings.themeMode == ThemeMode.LIGHT) { viewModel.setThemeMode(ThemeMode.LIGHT) }
            ChoiceRow("Dark", settings.themeMode == ThemeMode.DARK) { viewModel.setThemeMode(ThemeMode.DARK) }
            SwitchRow(
                title = "Dynamic color",
                subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "Follow your wallpaper's palette"
                } else {
                    "Dynamic color requires Android 12+"
                },
                checked = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                onCheckedChange = viewModel::setDynamicColor
            )

            HorizontalDivider()
            SectionTitle("Session defaults")

            SwitchRow(
                title = "Diagnostic HUD",
                subtitle = "Show FPS / latency / jitter overlay during sessions",
                checked = settings.hudEnabled,
                onCheckedChange = viewModel::setHudEnabled
            )
            SwitchRow(
                title = "Touchpad mode by default",
                subtitle = "Relative cursor with tap-to-click and two-finger scroll",
                checked = settings.touchpadDefault,
                onCheckedChange = viewModel::setTouchpadDefault
            )

            HorizontalDivider()
            SectionTitle("Performance preset")
            recommendation?.let { preset ->
                Text(
                    "Suggested right now: ${presetLabel(preset)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            PerformancePreset.entries.filter { it != PerformancePreset.LOW_LATENCY }.forEach { preset ->
                PresetRow(
                    preset = preset,
                    selected = settings.defaultPerformancePreset == preset,
                    onSelect = { viewModel.setDefaultPerformancePreset(preset) }
                )
            }
            SwitchRow(
                title = "Apply to all profiles",
                subtitle = "Overrides the preset stored on each connection profile",
                checked = settings.presetOverridesProfiles,
                onCheckedChange = viewModel::setPresetOverridesProfiles
            )

            HorizontalDivider()
            SectionTitle("Engine")
            SwitchRow(
                title = "Demo engine",
                subtitle = "Use the offline mock RDP engine instead of native FreeRDP — " +
                    "useful when the native libraries are not packaged in this build. Applies to the next connection.",
                checked = settings.demoEngine,
                onCheckedChange = viewModel::setDemoEngine
            )

            HorizontalDivider()
            SectionTitle("Trusted certificates")
            if (settings.trustedCertificates.isEmpty()) {
                Text(
                    "No certificates trusted yet. You'll be asked to verify a certificate " +
                        "the first time you connect to each server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            } else {
                settings.trustedCertificates.forEach { cert ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(cert.host, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    cert.fingerprint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            IconButton(
                                onClick = { viewModel.revokeCertificate(cert) },
                                modifier = Modifier.touchTarget()
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Revoke trust for ${cert.host}",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .touchTarget(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        Modifier
            .fillMaxWidth()
            .touchTarget(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.touchTarget()
        )
    }
}

private fun presetLabel(preset: PerformancePreset): String = when (preset) {
    PerformancePreset.ULTRA_LOW_LATENCY -> "Ultra-Low Latency"
    PerformancePreset.LOW_LATENCY -> "Low Latency"
    PerformancePreset.BALANCED -> "Balanced Mobile"
    PerformancePreset.DATA_SAVER -> "Data Saver"
    PerformancePreset.BATTERY_SAVER -> "Battery Saver"
}
