package io.github.kittkanev.axis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

@Composable
internal fun CloudScreen(model: AxisViewModel) {
    val context = LocalContext.current
    val cloud = (context.applicationContext as AxisApplication).cloud
    val session by cloud.state.collectAsStateWithLifecycle()
    val pushRunning by AxisPushService.running.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(session.baseUrl) }
    var ca by remember { mutableStateOf(session.ca) }
    var email by remember { mutableStateOf(session.email) }
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<JSONObject?>(null) }
    var release by remember { mutableStateOf<CloudUpdate?>(null) }
    var matchingUpdate by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var importFile by remember { mutableStateOf<Uri?>(null) }
    var consent by remember { mutableStateOf(false) }
    val conflict = stringResource(R.string.cloud_conflict)
    val finished = stringResource(R.string.cloud_finished)
    val failed = stringResource(R.string.cloud_failed)
    val empty = stringResource(R.string.cloud_empty)
    val old = stringResource(R.string.cloud_no_update)

    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = ""
        scope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: AxisException) {
                notice = if (error.httpStatus == 409) conflict else error.message ?: failed
            } catch (_: Exception) {
                notice = failed
            } finally {
                busy = false
            }
        }
    }

    fun stopPush() { context.stopService(Intent(context, AxisPushService::class.java)) }
    fun startPush() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, AxisPushService::class.java))
        } catch (_: IllegalStateException) {
            notice = failed
        }
    }

    val pushPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startPush() else notice = failed
    }
    val chooseCa = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            ca = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBounded(32_768).decodeToString() }
                    ?: throw IOException("Could not open certificate")
            }
            notice = finished
        }
    }
    val openWorkspace = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile = uri
    }
    val saveWorkspace = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) work {
            val bytes = model.exportWorkspace()
            withContext(Dispatchers.IO) {
                val encrypted = WorkspaceCipher.seal(bytes, passphrase)
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(encrypted) }
                    ?: throw IOException("Could not write workspace")
            }
            notice = finished
        }
    }
    val saveApk = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        val update = release
        if (uri != null && update != null && matchingUpdate) work {
            val file = withContext(Dispatchers.IO) { File.createTempFile("axis-update-", ".apk", context.cacheDir) }
            try {
                cloud.download(update, file)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: throw IOException("Could not write APK")
                }
                notice = finished
            } finally {
                file.delete()
            }
        }
    }

    LaunchedEffect(session.signedIn, session.email) {
        if (session.signedIn) plan = runCatching { cloud.get("subscription") }.getOrNull() else plan = null
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (notice.isNotEmpty()) Text(notice, style = MaterialTheme.typography.bodyMedium)
        Section(stringResource(R.string.cloud_account)) {
            OutlinedTextField(address, { address = it.take(512) }, label = { Text(stringResource(R.string.cloud_url)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            Row {
                TextButton(onClick = { chooseCa.launch(arrayOf("*/*")) }, enabled = !busy) {
                    Text(stringResource(if (ca.isEmpty()) R.string.cloud_ca_add else R.string.cloud_ca_replace))
                }
                if (ca.isNotEmpty()) TextButton(onClick = { ca = "" }, enabled = !busy) { Text(stringResource(R.string.cloud_ca_remove)) }
            }
            if (session.signedIn) {
                Text(session.email)
                Row {
                    OutlinedButton(onClick = { stopPush(); cloud.logout() }, enabled = !busy) { Text(stringResource(R.string.cloud_logout)) }
                    TextButton(onClick = { work { stopPush(); cloud.configure(address, ca); notice = finished } }, enabled = !busy) {
                        Text(stringResource(R.string.save_action))
                    }
                }
            } else {
                OutlinedTextField(email, { email = it.take(254) }, label = { Text(stringResource(R.string.cloud_email)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                OutlinedTextField(password, { password = it.take(128) }, label = { Text(stringResource(R.string.cloud_password)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { work { cloud.login(address, ca, email, password, false); password = ""; notice = finished } },
                        enabled = !busy && email.isNotBlank() && password.length >= 12) { Text(stringResource(R.string.cloud_login)) }
                    OutlinedButton(onClick = { work { cloud.login(address, ca, email, password, true); password = ""; notice = finished } },
                        enabled = !busy && email.isNotBlank() && password.length >= 12) { Text(stringResource(R.string.cloud_register)) }
                }
            }
        }

        Section(stringResource(R.string.cloud_workspace)) {
            OutlinedTextField(passphrase, { passphrase = it.take(256) }, label = { Text(stringResource(R.string.cloud_passphrase)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
            Text(stringResource(R.string.cloud_workspace_hint), style = MaterialTheme.typography.bodySmall)
            val enabled = !busy && passphrase.length >= 12
            Row {
                TextButton(onClick = { saveWorkspace.launch("workspace.axws") }, enabled = enabled) { Text(stringResource(R.string.cloud_export)) }
                TextButton(onClick = { openWorkspace.launch(arrayOf("*/*")) }, enabled = enabled) { Text(stringResource(R.string.cloud_import)) }
            }
            if (session.signedIn) Row {
                TextButton(onClick = { work {
                    val bytes = model.exportWorkspace()
                    val encrypted = withContext(Dispatchers.Default) { WorkspaceCipher.seal(bytes, passphrase, WORKSPACE_SYNC_LIMIT) }
                    cloud.uploadBlob(encrypted)
                    notice = finished
                } }, enabled = enabled) { Text(stringResource(R.string.cloud_upload)) }
                TextButton(onClick = { restoring = true }, enabled = enabled) { Text(stringResource(R.string.cloud_restore)) }
            }
        }

        if (session.signedIn) {
            Section(stringResource(R.string.cloud_plans)) {
                plan?.let { details ->
                    val tier = if (details.optString("tier") == "plus") "Plus" else "Free"
                    Text(stringResource(R.string.cloud_remaining, tier, details.optInt("remaining"), details.optInt("limit")))
                    Text(stringResource(R.string.cloud_plus_details, details.optInt("priceRub"), details.optInt("periodDays"), details.optInt("plusLimit")))
                    Text(stringResource(R.string.cloud_shared_capacity), style = MaterialTheme.typography.bodySmall)
                    val expiry = details.optLong("expiresAt")
                    if (expiry > 0) Text(stringResource(R.string.cloud_expiration,
                        java.time.Instant.ofEpochSecond(expiry).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()))
                    val checkout = Uri.parse(details.optString("checkoutUrl"))
                    if (checkout.scheme == "https" && checkout.host in setOf("boosty.to", "pay.rollypay.io")) {
                        OutlinedButton(onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, checkout)) }
                            catch (_: android.content.ActivityNotFoundException) { notice = failed }
                        }, enabled = !busy) { Text(stringResource(R.string.cloud_checkout)) }
                    } else Text(stringResource(R.string.cloud_checkout_pending), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { consent = true }, enabled = !busy && details.optBoolean("enabled")) {
                        Text(stringResource(R.string.cloud_enable_deepseek))
                    }
                }
                TextButton(onClick = { work { plan = cloud.get("subscription") } }, enabled = !busy) { Text(stringResource(R.string.cloud_refresh)) }
            }
            Section(stringResource(R.string.cloud_reminders)) {
                AxisSwitch(stringResource(R.string.cloud_push_toggle), pushRunning) { enable ->
                    if (!enable) stopPush()
                    else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        pushPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else startPush()
                }
            }
        }

        Section(stringResource(R.string.cloud_updates)) {
            OutlinedButton(onClick = { work {
                cloud.configure(address, ca)
                val update = cloud.version()
                release = update
                val installed = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                matchingUpdate = update.packageName == context.packageName && update.code > installed
                if (!matchingUpdate) notice = old
            } }, enabled = !busy && address.isNotBlank()) { Text(stringResource(R.string.cloud_check_updates)) }
            release?.let { update ->
                Text(update.version, style = MaterialTheme.typography.titleMedium)
                if (update.changelog.isNotBlank()) MarkdownMessage(update.changelog)
                if (matchingUpdate) TextButton(onClick = { saveApk.launch("Axis-${update.version}.apk") }, enabled = !busy) {
                    Text(stringResource(R.string.cloud_download))
                }
            }
        }
    }

    if (restoring || importFile != null) AlertDialog(
        onDismissRequest = { restoring = false; importFile = null },
        title = { Text(stringResource(R.string.cloud_replace_workspace)) },
        text = { Text(stringResource(R.string.cloud_replace_detail)) },
        confirmButton = { TextButton(onClick = {
            val fromServer = restoring
            val file = importFile
            restoring = false
            importFile = null
            work {
                if (fromServer) {
                    val backup = cloud.restoreBlob()
                    val encrypted = backup.bytes ?: throw AxisException(empty)
                    val plain = withContext(Dispatchers.Default) { WorkspaceCipher.open(encrypted, passphrase, WORKSPACE_SYNC_LIMIT) }
                    model.importWorkspace(plain)
                    cloud.markRevision(backup.revision)
                } else {
                    val plain = withContext(Dispatchers.IO) {
                        val encrypted = context.contentResolver.openInputStream(requireNotNull(file))?.use { it.readBounded(WORKSPACE_FILE_LIMIT) }
                            ?: throw IOException("Could not open workspace")
                        WorkspaceCipher.open(encrypted, passphrase)
                    }
                    model.importWorkspace(plain)
                }
                notice = finished
            }
        }) { Text(stringResource(R.string.cloud_restore)) } },
        dismissButton = { TextButton(onClick = { restoring = false; importFile = null }) { Text(stringResource(R.string.cancel_action)) } },
    )
    if (consent) AlertDialog(
        onDismissRequest = { consent = false },
        title = { Text(stringResource(R.string.cloud_enable_deepseek)) },
        text = { Text(stringResource(R.string.cloud_coarena_privacy)) },
        confirmButton = { TextButton(onClick = { consent = false; model.enableHostedProfile() }) { Text(stringResource(R.string.cloud_enable)) } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text(stringResource(R.string.cancel_action)) } },
    )
}
