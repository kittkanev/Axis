package io.github.kittkanev.axis

import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ColumnScope.DocumentScreen(model: AxisViewModel) {
    val document by model.document.collectAsStateWithLifecycle()
    val busy by model.documentBusy.collectAsStateWithLifecycle()
    val result by model.documentNotice.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.readDocument(uri)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        val uri = if (response.resultCode == Activity.RESULT_OK) response.data?.data else null
        model.finishDocumentSave(uri)
    }
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Файлы", style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.document_help))
        OutlinedButton(enabled = !busy, onClick = { confirm = true }) {
            Text(stringResource(R.string.choose_file))
        }
        OutlinedButton(enabled = !busy, onClick = model::newDocument) { Text(stringResource(R.string.new_text)) }
        val file = document
        if (file != null) {
            Text(file.name, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                file.text, model::editDocument, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text(stringResource(R.string.document_text)) }, minLines = 5, maxLines = 8,
            )
            Button(enabled = !busy, onClick = {
                try {
                    model.prepareDocumentSave()?.let(save::launch)
                } catch (_: ActivityNotFoundException) {
                    model.finishDocumentSave(null)
                    model.documentError("На устройстве нет системного выбора файлов.")
                }
            }) { Text(stringResource(R.string.save_copy)) }
        }
        result?.let { Text(it) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.open_document)) },
            text = { Text(stringResource(R.string.select_text_file_prompt)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    try {
                        open.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        model.documentError("На устройстве нет системного выбора файлов.")
                    }
                }) { Text(stringResource(R.string.select_action)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel_action)) } },
        )
    }
}
