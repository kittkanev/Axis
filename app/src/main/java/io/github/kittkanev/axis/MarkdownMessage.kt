package io.github.kittkanev.axis

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownText
import org.commonmark.parser.Parser

private val markdownParser = Parser.builder().build()

@Composable
internal fun MarkdownMessage(value: String, color: Color = Color.Unspecified) {
    val annotated = remember(value) {
        buildAnnotatedString { appendMarkdown(markdownParser.parse(value), 0) }
    }
    SelectionContainer { Text(annotated, color = color, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
internal fun CopyReply(text: String) {
    val context = LocalContext.current
    val label = stringResource(R.string.copy_reply)
    TextButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text)) }) {
        Text(label)
    }
}

private fun AnnotatedString.Builder.appendMarkdown(node: Node, depth: Int) {
    if (depth >= 40) return
    fun children() {
        var child = node.firstChild
        while (child != null) {
            appendMarkdown(child, depth + 1)
            child = child.next
        }
    }
    fun newline() { if (length > 0) append('\n') }
    when (node) {
        is MarkdownText -> append(node.literal)
        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.literal) }
        is FencedCodeBlock -> { newline(); withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.literal) }; newline() }
        is IndentedCodeBlock -> { newline(); withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.literal) }; newline() }
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children() }
        is Heading -> { newline(); withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }; newline() }
        is Paragraph -> { children(); newline() }
        is ListItem -> { newline(); append(if (node.parent is OrderedList) "${itemNumber(node)}. " else "• "); children(); newline() }
        is BlockQuote -> { newline(); append("│ "); children(); newline() }
        is ThematicBreak -> { newline(); append("────────\n") }
        is Link -> { children(); if (node.destination.startsWith("https://") || node.destination.startsWith("http://")) append(" (${node.destination})") }
        is SoftLineBreak, is HardLineBreak -> append('\n')
        is HtmlInline -> append(node.literal)
        is HtmlBlock -> { append(node.literal); newline() }
        else -> children()
    }
}

private fun itemNumber(item: ListItem): Int {
    var index = (item.parent as OrderedList).startNumber
    var previous = item.previous
    while (previous != null) { index++; previous = previous.previous }
    return index
}
