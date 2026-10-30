package com.filesearch.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
    Column(
        modifier =
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                .testTag("settingsScreen"),
    ) {
        Section("Folders to watch") {
            folders.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = onPickFolder, modifier = Modifier.padding(top = 4.dp)) { Text("Add folder") }
        }
        Section("Excluded types") {
            Text(excludedTypes.joinToString(", ").ifEmpty { "none" })
        }
        Section("Battery rule") {
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
            )
        }
        Section("Models") {
            Text("Packs download once over Wi-Fi, checksum-verified, deletable.")
        }
        Section("Remove app data") {
            Text(wipeSummary, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onWipe, modifier = Modifier.padding(top = 4.dp).testTag("wipeButton")) {
                Text("Remove app data")
            }
        }
        validationErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Section(title: String, body: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    body()
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/** Host-owned defaults: both cloud toggles OFF (design §7). */
fun defaultVoiceToggles(): VoiceToggles = VoiceService.defaultToggles()
