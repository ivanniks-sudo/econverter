package com.econverter.app

import org.apache.commons.compress.MemoryLimitException
import java.io.File
import java.io.IOException
import java.io.InputStream

internal val BOOK_ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z")
internal enum class ArchiveFailure(val messageRes: Int) {
    INVALID(R.string.archive_invalid),
    EMPTY(R.string.archive_empty),
    UNSAFE(R.string.archive_unsafe),
    LIMIT(R.string.archive_limit),
}
internal class ArchiveImportException(val reason: ArchiveFailure, val skipped: Int = 0) : IOException()

/** Shares ZIP's byte/book/entry budget and private storage; ZIP decoding itself stays untouched. */
internal fun extractOtherArchiveBooks(
    input: InputStream,
    directory: File,
    extension: String,
    formats: Set<String>,
    budget: ZipBudget,
    readerFactory: (File, String) -> BookArchiveReader = ::openBookArchive,
    checkCancelled: () -> Unit = {},
): ExtractedZip {
    val limits = budget.limits
    val source = File(directory, "source.$extension")
    var created = false
    var accepted = false
    try {
        if (++budget.archives > 64) throw ArchiveImportException(ArchiveFailure.LIMIT)
        created = directory.mkdirs()
        if (!created) throw IOException("Could not create archive directory")
        var compressed = 0L
        source.outputStream().use { output ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                compressed += count
                budget.compressedBytes += count
                if (compressed > limits.archiveBytes || budget.compressedBytes > limits.totalBytes) throw ArchiveImportException(ArchiveFailure.LIMIT)
                output.write(buffer, 0, count)
            }
        }
        // Prevent renamed TAR, gzip and other formats from entering a generic archive reader.
        val magic = source.inputStream().use { it.readBytesPrefix(8) }
        val validMagic = when (extension) {
            "7z" -> magic.take(6) == listOf(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c)
            "rar" -> magic.take(7) == listOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0) || magic == listOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 1, 0)
            else -> false
        }
        if (!validMagic) throw ArchiveImportException(ArchiveFailure.INVALID)
        if (extension == "rar") validateRarHeader(source, checkCancelled)
        val books = mutableListOf<File>()
        var skipped = 0
        var declared = 0L
        var actualTotal = 0L
        readerFactory(source, extension).use { archive ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                checkCancelled()
                val entry = archive.next() ?: break
                if (++budget.entries > limits.entries) throw ArchiveImportException(ArchiveFailure.LIMIT)
                val path = entry.name.replace('\\', '/')
                if (path.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(path) || path.split('/').any { it == ".." } || '\u0000' in path || entry.link) throw ArchiveImportException(ArchiveFailure.UNSAFE)
                if (entry.encrypted) throw ArchiveImportException(ArchiveFailure.INVALID)
                if (entry.size < 0) throw ArchiveImportException(ArchiveFailure.INVALID)
                if (entry.size > limits.fileBytes || entry.size > limits.totalBytes - declared) throw ArchiveImportException(ArchiveFailure.LIMIT)
                if (entry.directory) {
                    if (entry.size != 0L) throw ArchiveImportException(ArchiveFailure.INVALID)
                    continue
                }
                declared += entry.size
                val name = path.substringAfterLast('/')
                val supported = name.substringAfterLast('.', "").lowercase() in formats
                if (supported && ++budget.books > limits.books) throw ArchiveImportException(ArchiveFailure.LIMIT)
                val destination = if (supported) File(directory, "${budget.entries}/$name") else null
                if (destination != null) {
                    if (!destination.canonicalPath.startsWith(directory.canonicalPath + File.separator)) throw ArchiveImportException(ArchiveFailure.UNSAFE)
                    if (!destination.parentFile!!.mkdirs()) throw IOException("Could not create entry directory")
                }
                var actual = 0L
                val output = destination?.outputStream()
                try {
                    // Drain skipped entries too: solid archives may need them for later books,
                    // so their inflated bytes must also consume the resource budget.
                    while (true) {
                        checkCancelled()
                        val count = archive.read(buffer)
                        if (count < 0) break
                        if (count == 0) throw ArchiveImportException(ArchiveFailure.INVALID)
                        actual += count
                        actualTotal += count
                        budget.bytes += count
                        if (actual > limits.fileBytes || budget.bytes > limits.totalBytes || (actualTotal > 1024 * 1024 && actualTotal > maxOf(1, compressed) * limits.compressionRatio)) throw ArchiveImportException(ArchiveFailure.LIMIT)
                        output?.write(buffer, 0, count)
                    }
                } finally {
                    output?.close()
                }
                if (actual != entry.size) throw ArchiveImportException(ArchiveFailure.INVALID)
                if (destination != null) books += destination else skipped++
            }
        }
        if (books.isEmpty()) throw ArchiveImportException(ArchiveFailure.EMPTY, skipped)
        accepted = true
        return ExtractedZip(books, skipped)
    } catch (_: MemoryLimitException) {
        throw ArchiveImportException(ArchiveFailure.LIMIT)
    } catch (error: IOException) {
        throw (error as? ArchiveImportException ?: ArchiveImportException(ArchiveFailure.INVALID))
    } finally {
        if (created) {
            source.delete()
            if (!accepted) directory.deleteRecursively()
        }
    }
}

private fun InputStream.readBytesPrefix(length: Int): List<Int> {
    val bytes = mutableListOf<Int>()
    repeat(length) {
        val value = read()
        if (value >= 0) bytes += value
    }
    return bytes
}
