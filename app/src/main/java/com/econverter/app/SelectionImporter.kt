package com.econverter.app

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import java.io.File

internal const val ZIP_IMPORTS_DIRECTORY = "zip-imports"
internal data class ZipSource(val uri: Uri, val name: String, val file: File, val directory: File)
internal data class SelectionImport(
    val pairs: MutableList<Pair<Uri, String>> = mutableListOf(),
    val zipSources: MutableMap<Uri, ZipSource> = mutableMapOf(),
    var unsupported: Int = 0,
    var inaccessible: Int = 0,
    var archives: Int = 0,
    val issues: MutableList<Pair<String, ZipFailure>> = mutableListOf(),
)

internal fun importSelection(
    context: Context,
    candidates: List<Uri>,
    shared: Boolean,
    formats: Set<String>,
    directory: File,
    budget: ZipBudget,
    checkCancelled: () -> Unit,
): SelectionImport {
    val result = SelectionImport()
    for ((index, uri) in candidates.withIndex()) {
        checkCancelled()
        try {
            if (shared && uri.scheme != "content") {
                result.inaccessible++
                continue
            }
            val name = getFileName(context, uri)
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension == "zip") {
                result.archives++
                val archiveDirectory = File(directory, index.toString())
                var accepted = false
                try {
                    val extracted = context.contentResolver.openInputStream(uri)?.use {
                        extractZipBooks(it, archiveDirectory, formats, budget, checkCancelled)
                    } ?: throw java.io.IOException("No input stream")
                    val sources = extracted.books.associate { file ->
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) to ZipSource(uri, name, file, directory)
                    }
                    result.zipSources.putAll(sources)
                    result.pairs += sources.map { (bookUri, source) -> bookUri to source.file.name }
                    result.unsupported += extracted.skipped
                    accepted = true
                } catch (error: ZipImportException) {
                    result.unsupported += error.skipped
                    result.issues += name to error.reason
                } catch (error: java.io.IOException) {
                    result.issues += name to ZipFailure.INVALID
                } finally {
                    if (!accepted) archiveDirectory.deleteRecursively()
                }
            } else if (extension !in formats) {
                result.unsupported++
            } else {
                if (shared) {
                    val stream = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("No input stream")
                    stream.use { it.read() }
                }
                result.pairs += uri to name
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            result.inaccessible++
        }
    }
    return result
}
