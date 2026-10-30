package com.filesearch.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
 */

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
        Column(
            modifier =
                modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                    .testTag("settingsScreen"),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            if (validationErrors.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("validationErrors"),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        validationErrors.forEach { error ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 2.dp),
                            ) {
                                Icon(
                                    Icons.Filled.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }
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
                OutlinedButton(
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
            Section("Battery") {
                ToggleRow("Index on charger only", chargerOnly, onChargerOnlyChange, tag = "chargerSwitch")
                ToggleRow("Download models over Wi-Fi only", wifiOnlyModels, onWifiOnlyChange, tag = "wifiSwitch")
            }
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
            Section("Models") {
                Text(
                    "Packs download once over Wi-Fi, checksum-verified, deletable.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Section("Data") {
                Text(wipeSummary, style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = onWipe,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    modifier = Modifier.padding(top = 8.dp).testTag("wipeButton"),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Remove app data")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, body: @Composable () -> Unit) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
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
