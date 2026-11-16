package io.github.kittkanev.axis

import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Suppress("DEPRECATION")
internal fun installedApps(context: Context): List<DeviceApp> {
    val manager = context.packageManager
    return manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            if (activity.packageName == context.packageName || !activity.exported) return@mapNotNull null
            DeviceApp(info.loadLabel(manager).toString(), activity.packageName)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

internal fun openApp(context: Context, app: DeviceApp): Boolean {
    val launch = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
    return openActivity(context, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openActivity(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: android.content.ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@Composable
internal fun ColumnScope.DeviceScreen(model: AxisViewModel, state: AxisState) {
    val context = LocalContext.current
    val connected by DeviceControlService.isConnected.collectAsStateWithLifecycle()
    val apps = remember { installedApps(context) }
    val plan by model.devicePlan.collectAsStateWithLifecycle()
    val planning = state.planning
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var goal by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var disclose by rememberSaveable { mutableStateOf(false) }
    var approve by remember { mutableStateOf<List<DeviceStep>?>(null) }
    val target = apps.firstOrNull { it.packageName == selected }
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Управление", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(query, { query = it.take(100) }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.find_app)) }, singleLine = true)
        apps.asSequence().filter {
            it.label.contains(query.trim(), true) || it.packageName.contains(query.trim(), true)
        }.take(8).forEach { app ->
            TextButton(enabled = !planning, onClick = {
                if (selected != app.packageName) model.setDevicePlan("")
                selected = app.packageName
                error = null
            }) {
                Text(if (target == app) "${app.label} · выбрано" else app.label)
            }
        }
        target?.let { app ->
            Text("Выбрано: ${app.label}")
            OutlinedButton(onClick = {
                if (!openApp(context, app)) error = "Не удалось открыть ${app.label}."
            }) { Text(stringResource(R.string.open_app_action)) }
        }
        OutlinedButton(onClick = { disclose = true }) {
            Text(if (connected) "Служба Axis включена" else "Подключить управление")
        }
        if (target != null) {
            if (state.workspace.active != null) {
                OutlinedTextField(goal, { goal = it.take(2000) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.model_goal)) }, enabled = !planning, maxLines = 3)
                Text(stringResource(R.string.send_model_goal))
                if (planning) {
                    OutlinedButton(onClick = model::cancelPlanning) { Text(stringResource(R.string.cancel_request)) }
                } else {
                    OutlinedButton(onClick = {
                        model.proposeDevicePlan(target.label, goal.trim())
                    },
                        enabled = goal.isNotBlank() && !state.planning && state.workspace.active != null) { Text(stringResource(R.string.suggest_steps)) }
                }
            }
            Text(stringResource(R.string.device_steps_format))
            OutlinedTextField(plan, model::setDevicePlan, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.steps_in_app)) }, enabled = !planning, maxLines = 8)
            Button(enabled = connected && plan.isNotBlank() && !planning, onClick = {
                try {
                    approve = parseDevicePlan(plan)
                    error = null
                } catch (e: AxisException) {
                    error = e.message
                }
            }) { Text(stringResource(R.string.check_start)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (disclose) {
        AlertDialog(
            onDismissRequest = { disclose = false },
            title = { Text(stringResource(R.string.screen_access)) },
            text = { Text(stringResource(R.string.device_access_help)) },
            confirmButton = {
                TextButton(onClick = {
                    disclose = false
                    if (!openActivity(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
                        error = "Не удалось открыть настройки специальных возможностей."
                    }
                }) { Text(stringResource(R.string.android_settings)) }
            },
            dismissButton = { TextButton(onClick = { disclose = false }) { Text(stringResource(R.string.cancel_action)) } },
        )
    }
    val reviewed = approve
    if (reviewed != null && target != null) {
        AlertDialog(
            onDismissRequest = { approve = null },
            title = { Text(target.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    reviewed.forEachIndexed { index, step -> Text("${index + 1}. ${step.description}") }
                    Text(stringResource(R.string.step_preview_hint))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    approve = null
                    val service = DeviceControlService.instance
                    if (service?.start(target, reviewed) != true) error = "Служба управления недоступна."
                    else if (!openApp(context, target)) {
                        service.stop()
                        error = "Не удалось открыть ${target.label}."
                    }
                }) { Text(stringResource(R.string.open_show_steps)) }
            },
            dismissButton = { TextButton(onClick = { approve = null }) { Text(stringResource(R.string.cancel_action)) } },
        )
    }
}
