package io.github.kittkanev.axis

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val MAX_DOCUMENT_BYTES = 128 * 1024

internal data class AxisDocument(val name: String, val text: String, val source: Uri? = null)

internal fun readAxisDocument(resolver: ContentResolver, uri: Uri): AxisDocument {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw IOException()
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }.orEmpty().ifBlank { "document.txt" }
    val mime = resolver.getType(uri)
    if (mime?.startsWith("text/") != true && name.substringAfterLast('.').lowercase() !in setOf("txt", "md", "markdown")) {
        throw AxisException("Выберите текстовый файл или Markdown.")
    }
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_DOCUMENT_BYTES) throw AxisException("Размер файла превышает 128 КБ.")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: throw IOException()
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        .removePrefix("\uFEFF")
    if ('\u0000' in text) throw AxisException("Файл содержит двоичные данные.")
    return AxisDocument(name, text, uri)
}

internal fun writeAxisDocument(resolver: ContentResolver, uri: Uri, document: AxisDocument) {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri == document.source) {
        throw AxisException("Выберите новый файл для сохранения.")
    }
    val bytes = document.text.toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_DOCUMENT_BYTES) throw AxisException("Размер текста превышает 128 КБ.")
    resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IOException()
}

internal fun newDocumentIntent(document: AxisDocument): Intent {
    val markdown = document.name.substringAfterLast('.').lowercase() in setOf("md", "markdown")
    val base = document.name.substringBeforeLast('.').take(100).ifBlank { "document" }
    val extension = if (markdown) "md" else "txt"
    return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = if (markdown) "text/markdown" else "text/plain"
        putExtra(Intent.EXTRA_TITLE, "$base-axis.$extension")
    }
}
