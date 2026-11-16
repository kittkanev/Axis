package io.github.kittkanev.axis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun ConversationChooser(workspace: Workspace, model: AxisViewModel, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf<Conversation?>(null) }
    var delete by remember { mutableStateOf<Conversation?>(null) }
    var name by remember { mutableStateOf("") }
    val unnamed = stringResource(R.string.new_conversation)
    val shown = remember(workspace.threads, query, unnamed) {
        workspace.threads.filter { chat ->
            chat.title.ifEmpty { unnamed }.contains(query, true) || chat.messages.any { it.text.contains(query, true) }
        }
    }
    when {
        rename != null -> AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text(stringResource(R.string.rename_conversation)) },
            text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text(stringResource(R.string.conversation_name)) }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { rename?.let { model.renameConversation(it.id, name) }; close() }) { Text(stringResource(R.string.save_action)) } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text(stringResource(R.string.cancel_action)) } },
        )
        delete != null -> AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text(stringResource(R.string.delete_conversation)) },
            text = { Text(delete?.title.orEmpty().ifBlank { unnamed }) },
            confirmButton = { TextButton(onClick = { delete?.let { model.deleteConversation(it.id) }; close() }) { Text(stringResource(R.string.delete_action)) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text(stringResource(R.string.cancel_action)) } },
        )
        else -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.conversations)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it.take(100) }, label = { Text(stringResource(R.string.search_conversations)) }, singleLine = true)
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        shown.forEach { item ->
                            TextButton(onClick = { model.selectConversation(item.id); close() }, modifier = Modifier.fillMaxWidth()) {
                                Text(item.title.ifBlank { item.messages.firstOrNull()?.text?.take(55) ?: unnamed }, maxLines = 2)
                            }
                            Row {
                                TextButton(onClick = { name = item.title; rename = item }) { Text(stringResource(R.string.rename_conversation)) }
                                TextButton(onClick = { delete = item }) { Text(stringResource(R.string.delete_action)) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { model.createConversation(); close() }) { Text(unnamed) } },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel_action)) } },
        )
    }
}
