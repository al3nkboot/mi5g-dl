package com.mvrk.vrka.mi5g.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mvrk.vrka.AppSettings
import com.mvrk.vrka.SettingsRepository
import com.mvrk.vrka.VrkaChip
import com.mvrk.vrka.VrkaDivider
import com.mvrk.vrka.VrkaMonoFamily
import com.mvrk.vrka.VrkaOutlinedButton
import com.mvrk.vrka.VrkaSectionContainer
import com.mvrk.vrka.VrkaSettingRow
import com.mvrk.vrka.VrkaTextButton
import com.mvrk.vrka.VrkaTokens
import com.mvrk.vrka.mi5g.sync.SyncConfig
import com.mvrk.vrka.mi5g.sync.SyncManager
import com.mvrk.vrka.mi5g.update.Mi5gUpdateManager
import com.mvrk.vrka.mi5g.update.UpdateDownload
import com.mvrk.vrka.mi5g.update.UpdateRules
import com.mvrk.vrka.mi5g.update.UpdateUiState
import kotlinx.coroutines.launch

@Composable
private fun Mi5gSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked = checked,
    onCheckedChange = onChange,
    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = VrkaTokens.Accent),
)

@Composable
private fun Small(text: String, color: Color = VrkaTokens.TextSecondary, mono: Boolean = false, modifier: Modifier = Modifier) = Text(
    text,
    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontFamily = if (mono) VrkaMonoFamily else MaterialTheme.typography.bodySmall.fontFamily),
    color = color,
    modifier = modifier,
)

private fun ago(t: Long): String = if (t <= 0) "never" else DateUtils.getRelativeTimeSpanString(t).toString()

// ------------------------------------------------------------------------------------------ share

@Composable
fun ShareSettingsSection(settings: AppSettings, repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    VrkaSectionContainer(shape = RoundedCornerShape(16.dp)) {
        VrkaSettingRow(
            title = "Skip share sheet",
            subtitle = "Links shared to Mi5G Dl download instantly with your default Video/Audio settings.",
        ) { Mi5gSwitch(settings.shareSkipSheet) { scope.launch { repository.setShareSkipSheet(it) } } }
        VrkaDivider()
        VrkaSettingRow(
            title = "Precise trim by default",
            subtitle = "Re-encode at cut points (frame-accurate, slower) when trimming.",
        ) { Mi5gSwitch(settings.trimPreciseDefault) { scope.launch { repository.setTrimPreciseDefault(it) } } }
    }
}

// ------------------------------------------------------------------------------------------ updates

@Composable
fun UpdateSection(settings: AppSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val manager = remember { Mi5gUpdateManager.get(context) }
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var installMessage by remember { mutableStateOf<String?>(null) }
    UpdateSectionContent(
        state = state,
        dailyCheck = settings.updateDailyCheck,
        installMessage = installMessage,
        onCheck = manager::checkNow,
        onDownload = manager::startDownload,
        onCancel = manager::cancelDownload,
        onInstall = { file -> installMessage = manager.install(file) },
        onSaveToken = manager::saveToken,
        onClearToken = manager::clearToken,
        onDailyCheck = { enabled ->
            scope.launch { repository.setUpdateDailyCheck(enabled) }
            manager.setDailyCheck(enabled)
        },
    )
}

@Composable
fun UpdateSectionContent(
    state: UpdateUiState,
    dailyCheck: Boolean,
    installMessage: String?,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: (java.io.File) -> Unit,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
    onDailyCheck: (Boolean) -> Unit,
    initialShowHelp: Boolean = false,
    initialShowAdvanced: Boolean = false,
) {
    var showTokenDialog by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(initialShowHelp) }
    var showAdvanced by remember { mutableStateOf(initialShowAdvanced || state.hasToken) }
    VrkaSectionContainer(shape = RoundedCornerShape(16.dp)) {
        VrkaSettingRow(
            title = "Check for updates",
            subtitle = "Source: github.com/${UpdateRules.REPO} · installed ${state.installedVersionName.ifBlank { "?" }} (code ${state.installedVersionCode}) · last check ${ago(state.lastCheckAt)}",
            isSubtitleMono = true,
        ) {
            VrkaOutlinedButton(
                text = if (state.checking) "Checking…" else "Check",
                onClick = onCheck,
                enabled = !state.checking && state.rateLimitedUntil <= System.currentTimeMillis(),
                height = 32.dp,
            )
        }
        state.message?.let {
            Small(it, color = if (state.isError) VrkaTokens.Error else VrkaTokens.AccentLight, modifier = Modifier.padding(bottom = 8.dp))
        }
        val release = state.release
        if (state.updateAvailable && release != null) {
            Column(Modifier.padding(bottom = 10.dp)) {
                Text(
                    "${release.name} · ${UpdateRules.formatSize(release.apk.size)}" + (state.remoteVersionCode?.let { " · code $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = VrkaTokens.TextPrimary,
                )
                if (release.notes.isNotBlank()) {
                    Small(release.notes.take(1500), color = VrkaTokens.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                when (val d = state.download) {
                    UpdateDownload.Idle -> VrkaOutlinedButton(text = "Download & install", onClick = onDownload, height = 34.dp, borderColor = VrkaTokens.Accent)
                    is UpdateDownload.Running -> {
                        val p = if (d.total > 0) d.downloaded.toFloat() / d.total else 0f
                        LinearProgressIndicator(progress = { p }, color = VrkaTokens.Accent, trackColor = VrkaTokens.SurfaceInset, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Small("${UpdateRules.formatSize(d.downloaded)} / ${UpdateRules.formatSize(d.total)}", mono = true)
                            VrkaTextButton(text = "Cancel", onClick = onCancel)
                        }
                    }
                    is UpdateDownload.Ready -> {
                        Small("Downloaded and verified: same package, newer versionCode, signing certificate matches this app.", color = VrkaTokens.AccentLight)
                        Spacer(Modifier.height(6.dp))
                        VrkaOutlinedButton(text = "Install", onClick = { onInstall(d.file) }, height = 34.dp, borderColor = VrkaTokens.Accent)
                    }
                    is UpdateDownload.Failed -> {
                        Small(d.message, color = VrkaTokens.Error)
                        Spacer(Modifier.height(6.dp))
                        VrkaOutlinedButton(text = "Retry download", onClick = onDownload, height = 34.dp)
                    }
                }
                installMessage?.let { Small(it, color = VrkaTokens.AccentLight, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
        VrkaDivider()
        VrkaSettingRow(
            title = "Daily background check",
            subtitle = "Checks once a day (when online) and shows a notification if a new version is out. No token or sign-in needed.",
        ) { Mi5gSwitch(dailyCheck, onDailyCheck) }
        VrkaDivider()
        VrkaTextButton(
            text = if (showAdvanced) "Advanced: GitHub token (optional) ▲" else "Advanced: GitHub token (optional) ▼",
            onClick = { showAdvanced = !showAdvanced },
            color = VrkaTokens.TextSecondary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        if (showAdvanced) {
            Small(TOKEN_WHY, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
            VrkaSettingRow(
                title = "GitHub token",
                subtitle = if (state.hasToken) "Saved, encrypted with Android Keystore (${state.tokenHint ?: "…"}). Sent only to api.github.com."
                else "Not set. Not needed while the repository is public.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.hasToken) VrkaTextButton(text = "Remove", onClick = onClearToken)
                    VrkaOutlinedButton(text = if (state.hasToken) "Replace" else "Add token", onClick = { showTokenDialog = true }, height = 32.dp)
                }
            }
            VrkaTextButton(
                text = if (showHelp) "Hide: how to create the token ▲" else "How to create the token ▼",
                onClick = { showHelp = !showHelp },
                color = VrkaTokens.AccentLight,
            )
            if (showHelp) {
                Small(TOKEN_HELP, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
            }
        }
    }
    if (showTokenDialog) {
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            title = { Text("GitHub token") },
            text = {
                Column {
                    Small("Optional. Fine-grained token with read-only Contents access to ${UpdateRules.REPO}. Stored encrypted on this phone only; never synced.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it.trim() },
                        label = { Text("github_pat_…") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = {
                VrkaTextButton(text = "Save", enabled = token.length >= 20, color = VrkaTokens.Accent, onClick = {
                    onSaveToken(token); showTokenDialog = false
                })
            },
            dismissButton = { VrkaTextButton(text = "Cancel", onClick = { showTokenDialog = false }) },
        )
    }
}

val TOKEN_WHY = """Updates work without a token: ${UpdateRules.REPO} is public. A token is only needed if the repository is made private, or to avoid GitHub's limit of ${UpdateRules.UNAUTHENTICATED_LIMIT_PER_HOUR} unauthenticated requests per hour (shared by your network). One check uses one request."""

const val TOKEN_HELP = """1. On GitHub (signed in as al3nkboot): Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token.
2. Name: "Mi5G Dl updater"; Expiration: e.g. 1 year.
3. Resource owner: al3nkboot. Repository access: "Only select repositories" → al3nkboot/mi5g-dl.
4. Permissions → Repository permissions → Contents: Read-only (Metadata: Read-only is added automatically). Nothing else.
5. Generate, copy the token (starts with github_pat_) and paste it here. Repeat on each phone.
The token can only read this one repository. If it expires, checks show "401": create a new one or remove it (updates keep working without a token while the repo is public)."""

// ------------------------------------------------------------------------------------------ sync

@Composable
fun SyncSection() {
    val context = LocalContext.current
    val manager = remember { SyncManager.get(context) }
    val config by manager.config.collectAsState()
    val running by manager.running.collectAsState()
    val scope = rememberCoroutineScope()
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) { manager.setFile(uri); scope.launch { manager.syncNow("first") } }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { manager.setFile(uri); scope.launch { manager.syncNow("first") } }
    }
    SyncSectionContent(
        config = config,
        running = running,
        onCreate = { create.launch("mi5g-dl-sync.json") },
        onPick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
        onSyncNow = { scope.launch { manager.syncNow("manual") } },
        onAutoOnOpen = manager::setAutoOnOpen,
        onInterval = manager::setIntervalHours,
        onDisconnect = manager::disconnect,
    )
}

@Composable
fun SyncSectionContent(
    config: SyncConfig,
    running: Boolean,
    onCreate: () -> Unit,
    onPick: () -> Unit,
    onSyncNow: () -> Unit,
    onAutoOnOpen: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
    onDisconnect: () -> Unit,
) {
    VrkaSectionContainer(shape = RoundedCornerShape(16.dp)) {
        Small(
            "Keep settings and download history in one JSON file on Google Drive (or any storage). " +
                "Pick the same file on both phones. No sign-in; the GitHub token, browser data and download folder are never synced.",
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        if (config.fileUri == null) {
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VrkaOutlinedButton(text = "Create file on Drive", onClick = onCreate, height = 34.dp, borderColor = VrkaTokens.Accent)
                VrkaOutlinedButton(text = "Pick file", onClick = onPick, height = 34.dp)
            }
            Small("Tip: in the file picker choose Google Drive from the side menu.", color = VrkaTokens.TextTertiary, modifier = Modifier.padding(bottom = 8.dp))
        } else {
            VrkaSettingRow(
                title = config.fileLabel ?: "Sync file",
                subtitle = "File ID: ${config.fileId ?: "assigned on first sync"}\nThis device: ${config.deviceName}" +
                    (if (config.otherDevices.isNotEmpty()) "\nAlso syncing: ${config.otherDevices.joinToString()}" else ""),
                isSubtitleMono = true,
            ) {
                VrkaOutlinedButton(text = if (running) "Syncing…" else "Sync now", onClick = onSyncNow, enabled = !running, height = 32.dp)
            }
            Small(
                "Last sync: ${ago(config.lastSyncAt)}" + (config.lastMessage?.let { " · $it" } ?: ""),
                color = if (config.lastOk) VrkaTokens.TextSecondary else VrkaTokens.Error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            VrkaDivider()
            VrkaSettingRow(title = "Sync when the app opens", subtitle = null) { Mi5gSwitch(config.autoOnOpen, onAutoOnOpen) }
            Text("Background sync", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = VrkaTokens.TextPrimary)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Off", 6 to "6 h", 12 to "12 h", 24 to "24 h").forEach { (h, label) ->
                    VrkaChip(selected = config.intervalHours == h, onClick = { onInterval(h) }, label = label)
                }
            }
            VrkaDivider()
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VrkaTextButton(text = "Change file", onClick = onPick)
                VrkaTextButton(text = "Disconnect", onClick = onDisconnect, color = VrkaTokens.Error)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ YouTube account

@Composable
fun YouTubeAccountSection() {
    val context = LocalContext.current
    val manager = remember { com.mvrk.vrka.mi5g.youtube.YouTubeAccountManager.get(context) }
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { manager.refresh() }
    YouTubeAccountSectionContent(
        state = state,
        onSignIn = { signIn.launch(android.content.Intent(context, com.mvrk.vrka.mi5g.youtube.YouTubeSignInActivity::class.java)) },
        onSignOut = { scope.launch { manager.signOut() } },
    )
}

@Composable
fun YouTubeAccountSectionContent(
    state: com.mvrk.vrka.mi5g.youtube.YouTubeAccountState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    VrkaSectionContainer(shape = RoundedCornerShape(16.dp)) {
        VrkaSettingRow(
            title = if (state.signedIn) "Signed in to YouTube" else "Not signed in",
            subtitle = if (state.signedIn) "${state.cookieCount} cookies saved ${ago(state.savedAt)} · used only for YouTube links"
            else "Optional. Helps when YouTube asks to \"confirm you're not a bot\" or for age-restricted / members videos.",
        ) {
            if (state.signedIn) VrkaTextButton(text = "Sign out", onClick = onSignOut, color = VrkaTokens.Error)
            else VrkaOutlinedButton(text = "Sign in…", onClick = onSignIn, height = 32.dp, borderColor = VrkaTokens.Accent)
        }
        state.message?.let { Small(it, color = if (state.isError) VrkaTokens.Error else VrkaTokens.AccentLight, modifier = Modifier.padding(bottom = 6.dp)) }
        if (state.signedIn) {
            VrkaTextButton(text = "Sign in again (refresh cookies)", onClick = onSignIn, color = VrkaTokens.AccentLight)
        }
        Small(YOUTUBE_ACCOUNT_HELP, color = VrkaTokens.TextTertiary, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
    }
}

const val YOUTUBE_ACCOUNT_HELP = "Sign-in opens Google in Mi5G Dl's private built-in browser. Afterwards only youtube.com/google.com cookies are saved, in app-private storage on this phone: never synced, never logged, never sent anywhere except YouTube by yt-dlp. Google may block sign-in in embedded browsers (\"This browser or app may not be secure\"); downloads still work without it. Signing in does not always beat the bot check, which mostly depends on your network. Sign out deletes the cookies."
