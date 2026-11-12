package dev.wetube.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wetube.app.BuildConfig
import dev.wetube.app.data.backend.BackendType
import dev.wetube.app.data.prefs.AppSettings
import dev.wetube.app.di.ServiceLocator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val locator: ServiceLocator) : ViewModel() {

    val settings: StateFlow<AppSettings> = locator.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun update(block: (AppSettings) -> AppSettings) {
        viewModelScope.launch { locator.settings.update(block) }
    }

    companion object {
        fun factory(locator: ServiceLocator) = viewModelFactory {
            initializer { SettingsViewModel(locator) }
        }
    }
}

@Composable
fun SettingsScreen(
    locator: ServiceLocator,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(locator))
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Section("Backend")
        Text(
            "WeTube talks to YouTube directly by default, with no account and no " +
                "attestation. If you would rather route metadata through an independent " +
                "instance, pick Invidious or Piped below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        BackendType.entries.forEach { type ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = settings.backend == type,
                        onClick = { viewModel.update { it.copy(backend = type) } },
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = settings.backend == type,
                    onClick = { viewModel.update { it.copy(backend = type) } },
                )
                Text(type.label)
            }
        }

        Spacer(Modifier.height(8.dp))
        HostEditor(
            label = "Invidious host",
            value = settings.invidiousHost,
            onApply = { host -> viewModel.update { it.copy(invidiousHost = host, backend = BackendType.INVIDIOUS) } },
        )
        Spacer(Modifier.height(8.dp))
        HostEditor(
            label = "Piped host",
            value = settings.pipedHost,
            onApply = { host -> viewModel.update { it.copy(pipedHost = host, backend = BackendType.PIPED) } },
        )
        Spacer(Modifier.height(8.dp))
        HostEditor(
            label = "Trending region",
            value = settings.region,
            onApply = { region -> viewModel.update { it.copy(region = region.uppercase()) } },
        )

        Section("Playback")
        SwitchRow(
            title = "Audio only",
            subtitle = "Start videos in the background as audio.",
            checked = settings.audioOnly,
            onChange = { value -> viewModel.update { it.copy(audioOnly = value) } },
        )
        Text(
            "Default speed: ${settings.playbackSpeed}×",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
            value = settings.playbackSpeed,
            onValueChange = { value ->
                val snapped = (value * 4).toInt() / 4f
                viewModel.update { it.copy(playbackSpeed = snapped.coerceIn(0.25f, 4f)) }
            },
            valueRange = 0.25f..4f,
            steps = 14,
        )

        Section("Clean-up")
        SwitchRow(
            title = "SponsorBlock",
            subtitle = "Skip sponsor, intro and interaction segments automatically.",
            checked = settings.sponsorBlockEnabled,
            onChange = { value -> viewModel.update { it.copy(sponsorBlockEnabled = value) } },
        )
        SwitchRow(
            title = "Return YouTube Dislike",
            subtitle = "Show the community dislike count.",
            checked = settings.showDislikes,
            onChange = { value -> viewModel.update { it.copy(showDislikes = value) } },
        )
        SwitchRow(
            title = "Proxy streams through the instance",
            subtitle = "Fixes throttled or 403 media links on some networks. Uses more instance bandwidth.",
            checked = settings.proxyStreams,
            onChange = { value -> viewModel.update { it.copy(proxyStreams = value) } },
        )

        Section("Privacy")
        Text(
            "No ads. No trackers. No account. No analytics.\n\n" +
                "History, favourites and resume points stay on this device and are excluded " +
                "from cloud backup and device transfer. Requests go to YouTube in direct " +
                "mode, or to the instance you choose — nothing else.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("About")
        Text("WeTube ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Open-source client. Learnt from how the official app is structured, " +
                "written from scratch — no decompiled code is included.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun HostEditor(label: String, value: String, onApply: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(0.dp))
        Button(
            onClick = { onApply(text.trim()) },
            modifier = Modifier.padding(start = 8.dp),
        ) { Text("Apply") }
    }
}
