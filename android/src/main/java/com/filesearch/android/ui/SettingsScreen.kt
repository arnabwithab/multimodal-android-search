package com.filesearch.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.filesearch.android.VoiceToggles
import com.filesearch.android.VoiceService

/**
 * F020 settings screen (design §4.3 + §7/§8). Pure presentational: every value
 * arrives as a param (engine SettingsState + VoiceToggles mapped by the host),
 * every change leaves via callback. Cloud toggles default OFF on install via
 * VoiceService.defaultToggles(); provider names/keys stay in
 * EncryptedSharedPreferences, never shown here.
 *
 * Stock M3 Scaffold + TopAppBar + LazyColumn: the Scaffold's Surface supplies
 * the content color (a bare Column left heading text black-on-dark), the
 * TopAppBar keeps the heading visible, and the list scrolls to every section.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    folders: List<String>,
    excludedTypes: List<String>,
    chargerOnly: Boolean,
    wifiOnlyModels: Boolean,
    toggles: VoiceToggles,
    sttProviderLabel: String,
    ttsProviderLabel: String,
    validationErrors: List<String>,
    wipeSummary: String,
    onPickFolder: () -> Unit,
    onChargerOnlyChange: (Boolean) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onCloudSttChange: (Boolean) -> Unit,
    onCloudTtsChange: (Boolean) -> Unit,
    onWipe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FileSearchTheme {
        Scaffold(
            modifier = modifier.fillMaxSize().testTag("settingsScreen"),
            topBar = { TopAppBar(title = { Text("Settings") }) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            ) {
                if (validationErrors.isNotEmpty()) {
                    item {
                        Card(
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                .testTag("validationErrors"),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                validationErrors.forEach { error ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    ) {
                                        Icon(Icons.Filled.ErrorOutline, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(error, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Section("Folders") {
                        if (folders.isEmpty()) {
                            Text(
                                "No folders yet — add one to start indexing.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        folders.forEach {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Button(
                            onClick = onPickFolder,
                            modifier = Modifier.padding(top = 8.dp).testTag("addFolderButton"),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add folder")
                        }
                        Text(
                            "Skipped types: " + excludedTypes.joinToString(", ").ifEmpty { "none" },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                item {
                    Section("Battery") {
                        ToggleRow("Index on charger only", chargerOnly, onChargerOnlyChange, tag = "chargerSwitch")
                        ToggleRow("Download models over Wi-Fi only", wifiOnlyModels, onWifiOnlyChange, tag = "wifiSwitch")
                    }
                }
                item {
                    Section("Voice") {
                        ToggleRow(
                            "Cloud transcription ($sttProviderLabel)",
                            toggles.cloudStt,
                            onCloudSttChange,
                            tag = "cloudSttSwitch",
                        )
                        ToggleRow(
                            "Cloud read-aloud ($ttsProviderLabel)",
                            toggles.cloudTts,
                            onCloudTtsChange,
                            tag = "cloudTtsSwitch",
                        )
                        Text(
                            "Off by default. On-device speech is free and offline; cloud sends audio " +
                                "to the named provider using your key, billed per minute.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                item {
                    Section("Models") {
                        Text(
                            "Packs download once over Wi-Fi, checksum-verified, deletable.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item {
                    Section("Data") {
                        Text(wipeSummary, style = MaterialTheme.typography.bodyMedium)
                        // Secondary destructive action: text button so Add folder
                        // stays the screen's one primary action.
                        TextButton(
                            onClick = onWipe,
                            modifier = Modifier.padding(top = 8.dp).testTag("wipeButton"),
                        ) {
                            Text("Remove app data", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, body: @Composable () -> Unit) {
    // Stock Card defaults (no custom shape/colors): M3 picks the container.
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Column(modifier = Modifier.padding(top = 8.dp)) { body() }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/** Host-owned defaults: both cloud toggles OFF (design §7). */
fun defaultVoiceToggles(): VoiceToggles = VoiceService.defaultToggles()
