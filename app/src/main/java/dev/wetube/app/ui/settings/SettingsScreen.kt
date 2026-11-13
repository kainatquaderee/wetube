package dev.wetube.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import dev.wetube.app.BuildConfig
import dev.wetube.app.core.DEFAULT_INVIDIOUS_HOST
import dev.wetube.app.core.DEFAULT_PIPED_HOST
import dev.wetube.app.core.sanitizeBackendHost
import dev.wetube.app.data.auth.Account
import dev.wetube.app.data.backend.BackendType
import dev.wetube.app.data.local.MAX_BACKUP_BYTES
import dev.wetube.app.data.prefs.AppSettings
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.ui.formatSpeed
import dev.wetube.app.ui.friendlyError
import dev.wetube.app.ui.qualityHeights
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val locator: ServiceLocator) : ViewModel() {

    val settings: StateFlow<AppSettings> = locator.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val accounts: StateFlow<List<Account>> = locator.auth.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeIndex: StateFlow<Int> = locator.auth.activeIndex
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Last backup/restore/account message, shown under the buttons. */
    var message by mutableStateOf<String?>(null)

    fun update(block: (AppSettings) -> AppSettings) {
        viewModelScope.launch { locator.settings.update(block) }
    }

    fun switchTo(index: Int) {
        viewModelScope.launch {
            locator.auth.switchTo(index)
            message = null
        }
    }

    fun removeActive() {
        viewModelScope.launch {
            locator.auth.signOut()
            message = "Account removed from this device."
        }
    }

    fun exportBackup(onReady: (String?) -> Unit) {
        viewModelScope.launch {
            onReady(runCatching { locator.backup.export() }.getOrElse { null })
        }
    }

    fun restoreBackup(text: String) {
        viewModelScope.launch {
            message = runCatching { locator.backup.restore(text) }.fold(
                { snapshot ->
                    "Restored ${snapshot.favourites.size} favourites, ${snapshot.history.size} history " +
                        "items and ${snapshot.subscriptions.size} subscriptions."
                },
                { friendlyError(it) },
            )
        }
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
    onSignIn: () -> Unit,
    onCredits: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(locator))
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val activeIndex by viewModel.activeIndex.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.exportBackup { text ->
            if (text == null) {
                viewModel.message = "Could not build a backup."
                return@exportBackup
            }
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: error("could not open the destination")
            }.onSuccess {
                viewModel.message =
                    "Backup saved. Pick a Google Drive folder in the file picker to keep it in the cloud."
            }.onFailure {
                viewModel.message = "Could not save the backup: ${it.message}"
            }
        }
    }

    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Reject an oversized file before it is read into memory. Providers may
        // not report a length, in which case BackupStore still guards on parse.
        val declaredSize = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (declaredSize != null && declaredSize > MAX_BACKUP_BYTES.toLong()) {
            viewModel.message = "That backup is too large to import."
            return@rememberLauncherForActivityResult
        }
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text == null) {
            viewModel.message = "Could not read that file."
        } else {
            viewModel.restoreBackup(text)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            // Keep the last row (and the host fields while editing) clear of
            // the system navigation bar and the on-screen keyboard.
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Section("Accounts")
        AccountsSection(
            accounts = accounts,
            activeIndex = activeIndex,
            onSignIn = onSignIn,
            onSwitch = viewModel::switchTo,
            onRemove = viewModel::removeActive,
        )

        Section("Backup & restore")
        Text(
            "Save your subscriptions, favourites, history and preferences to a file, then open " +
                "that file on another phone to restore them. The file picker can write to Google " +
                "Drive, so the same backup works as cloud storage — no Google sign-in for the app " +
                "is involved and cookies are never included.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { saveBackup.launch("wetube-backup.json") }) { Text("Save backup") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                // `*/*` on purpose: many storage providers (including some
                // vendor file managers and cloud providers) report a `.json`
                // file as `application/octet-stream`, and a narrow filter then
                // hides the user's own backup. The file is validated on read.
                onClick = { openBackup.launch(arrayOf("*/*")) },
            ) { Text("Restore") }
        }
        viewModel.message?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

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

        // Direct mode never reads the instance hosts, so their fields are
        // disabled (not merely de-emphasised) while it is selected. They stay
        // editable in Invidious/Piped mode so a host can be checked before
        // switching over.
        val customHostsEnabled = settings.backend != BackendType.INNERTUBE

        // One draft per field; the single Save below commits all three together,
        // so a half-typed host can never be applied on its own the way the three
        // separate Apply buttons allowed.
        var invidiousDraft by remember(settings.invidiousHost) { mutableStateOf(settings.invidiousHost) }
        var pipedDraft by remember(settings.pipedHost) { mutableStateOf(settings.pipedHost) }
        var regionDraft by remember(settings.region) { mutableStateOf(settings.region) }

        HostEditor(
            label = "Invidious host",
            value = invidiousDraft,
            onValueChange = { invidiousDraft = it },
            enabled = customHostsEnabled,
        )
        Spacer(Modifier.height(8.dp))
        HostEditor(
            label = "Piped host",
            value = pipedDraft,
            onValueChange = { pipedDraft = it },
            enabled = customHostsEnabled,
        )
        Spacer(Modifier.height(8.dp))
        HostEditor(
            label = "Trending region",
            value = regionDraft,
            onValueChange = { regionDraft = it },
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val invidious = sanitizeBackendHost(invidiousDraft)
                    val piped = sanitizeBackendHost(pipedDraft)
                    val invalid = buildList {
                        if (invidious == null) add("Invidious")
                        if (piped == null) add("Piped")
                    }
                    viewModel.message = when (invalid.size) {
                        0 -> "Backend settings saved."
                        1 -> "That ${invalid.first()} host isn't a valid https address; using the default."
                        else -> "Those hosts aren't valid https addresses; using the defaults."
                    }
                    val region = regionDraft.trim().uppercase()
                    viewModel.update {
                        it.copy(
                            invidiousHost = invidious ?: DEFAULT_INVIDIOUS_HOST,
                            pipedHost = piped ?: DEFAULT_PIPED_HOST,
                            region = region.ifBlank { it.region },
                        )
                    }
                },
            ) { Text("Save backend settings") }
            if (!customHostsEnabled) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "Direct mode ignores these hosts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section("Playback")
        SwitchRow(
            title = "Audio only",
            subtitle = "Start videos in the background as audio.",
            checked = settings.audioOnly,
            onChange = { value -> viewModel.update { it.copy(audioOnly = value) } },
        )
        QualityRow(
            quality = settings.videoQuality,
            onChange = { value -> viewModel.update { it.copy(videoQuality = value) } },
        )
        SwitchRow(
            title = "Keep playing in the background",
            subtitle = "When off, playback pauses as soon as you leave the app.",
            checked = settings.backgroundPlayback,
            onChange = { value -> viewModel.update { it.copy(backgroundPlayback = value) } },
        )
        SwitchRow(
            title = "Captions on by default",
            subtitle = "Show subtitles whenever the video has a caption track.",
            checked = settings.captionsEnabled,
            onChange = { value -> viewModel.update { it.copy(captionsEnabled = value) } },
        )
        Text(
            "Default speed: ${formatSpeed(settings.playbackSpeed)}",
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

        Section("Content")
        SwitchRow(
            title = "Show mature content",
            subtitle = "Age-restricted videos stay hidden unless this is on. YouTube may still " +
                "require a signed-in browser session to play them.",
            checked = settings.showMature,
            onChange = { value -> viewModel.update { it.copy(showMature = value) } },
        )

        Section("Clean-up")
        SwitchRow(
            title = "SponsorBlock",
            subtitle = "Skip sponsor, intro and interaction segments automatically.",
            checked = settings.sponsorBlockEnabled,
            onChange = { value -> viewModel.update { it.copy(sponsorBlockEnabled = value) } },
        )
        SwitchRow(
            title = "Show likes and dislikes",
            subtitle = "Show the community like and dislike counts.",
            checked = settings.showDislikes,
            onChange = { value -> viewModel.update { it.copy(showDislikes = value) } },
        )
        SwitchRow(
            title = "Proxy streams through the instance",
            subtitle = "Send media links through your Invidious instance instead of YouTube. " +
                "Uses more instance bandwidth and can be slower.",
            checked = settings.proxyStreams,
            onChange = { value -> viewModel.update { it.copy(proxyStreams = value) } },
        )

        Section("About")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onCredits)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Credits", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "The people who build WeTube, founder first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        Text("WeTube ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "A clean-room, open-source client written from scratch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("Privacy")
        Text(
            "No ads. No trackers. No analytics.\n\n" +
                "History, favourites, subscriptions and resume points stay on this device " +
                "unless you save a backup. Account cookies stay in this app's private storage " +
                "and are deliberately left out of backup files. Requests go to YouTube in " +
                "direct mode, or to the instance you choose — nothing else.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AccountsSection(
    accounts: List<Account>,
    activeIndex: Int,
    onSignIn: () -> Unit,
    onSwitch: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    if (accounts.isEmpty()) {
        Text(
            "Signing in is optional. It brings in your own recommendations and subscriptions. " +
                "WeTube keeps working without an account, with the generic feed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = onSignIn) { Text("Sign in with Google") }
        return
    }

    accounts.forEachIndexed { index, account ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = index == activeIndex, onClick = { onSwitch(index) })
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = index == activeIndex, onClick = { onSwitch(index) })
            if (account.avatar.isNotBlank()) {
                AsyncImage(
                    model = account.avatar,
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(account.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (index == activeIndex) "Active account" else "Tap to switch",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onSignIn) { Text("Add account") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onRemove) { Text("Remove active") }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Feeds and subscriptions follow the active account immediately. Age-restricted videos " +
            "still depend on YouTube's browser challenge, which WeTube does not fake.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
private fun QualityRow(quality: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Video quality", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Auto uses the best resolution the connection can carry. A fixed height applies " +
                    "to every video; the closest one below is used when a video lacks it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(if (quality <= 0) "Auto" else "${quality}p")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(if (quality <= 0) "Auto  ✓" else "Auto") },
                    onClick = {
                        open = false
                        onChange(0)
                    },
                )
                qualityHeights.forEach { height ->
                    DropdownMenuItem(
                        text = { Text(if (height == quality) "${height}p  ✓" else "${height}p") },
                        onClick = {
                            open = false
                            onChange(height)
                        },
                    )
                }
            }
        }
    }
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
private fun HostEditor(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}
