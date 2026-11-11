package com.econverter.app

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipFile

internal data class ZipLimits(
    val archiveBytes: Long = 128L * 1024 * 1024,
    val fileBytes: Long = 64L * 1024 * 1024,
    val totalBytes: Long = 256L * 1024 * 1024,
    val entries: Int = 2048,
    val books: Int = 256,
    val compressionRatio: Long = 200,
)

// Shared across every archive in an import and charged even for subsequently rejected archives.
internal class ZipBudget(val limits: ZipLimits = ZipLimits(), var bytes: Long = 0, var books: Int = 0) {
    var entries = 0
    var archives = 0
    var compressedBytes = 0L
}

internal enum class ZipFailure(val messageRes: Int) {
    INVALID(R.string.zip_invalid),
    EMPTY(R.string.zip_empty),
    UNSAFE(R.string.zip_unsafe),
    LIMIT(R.string.zip_limit),
}

internal class ZipImportException(val reason: ZipFailure, val skipped: Int = 0) : IOException()
internal data class ExtractedZip(val books: List<File>, val skipped: Int)

/** Spool a bounded ZIP to disk, then stream individual entries; never trust header sizes alone. */
internal fun extractZipBooks(
    input: InputStream,
    directory: File,
    formats: Set<String>,
    budget: ZipBudget,
    checkCancelled: () -> Unit = {},
): ExtractedZip {
    val limits = budget.limits
    val archive = File(directory, "source.zip")
    var complete = false
    var created = false
    try {
        if (++budget.archives > 64) throw ZipImportException(ZipFailure.LIMIT)
        created = directory.mkdirs()
        if (!created) throw IOException("Could not create ZIP import directory")
        archive.outputStream().use { output ->
            val buffer = ByteArray(32 * 1024)
            var compressed = 0L
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                compressed += count
                budget.compressedBytes += count
                if (compressed > limits.archiveBytes || budget.compressedBytes > limits.totalBytes) throw ZipImportException(ZipFailure.LIMIT)
                output.write(buffer, 0, count)
            }
        }
        val books = mutableListOf<File>()
        var skipped = 0
        var declaredTotal = 0L
        val encoding = validateZipDirectory(archive, budget, checkCancelled)
        ZipFile(archive, encoding).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                checkCancelled()
                val entry = entries.nextElement()
                if (++budget.entries > limits.entries) throw ZipImportException(ZipFailure.LIMIT)
                val path = entry.name.replace('\\', '/')
                if (path.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(path) || path.split('/').any { it == ".." } || '\u0000' in path) {
                    throw ZipImportException(ZipFailure.UNSAFE)
                }
                if (entry.isDirectory) continue
                if (entry.size < 0 || entry.compressedSize < 0) throw ZipImportException(ZipFailure.INVALID)
                if (entry.size > limits.fileBytes || entry.size > limits.totalBytes - declaredTotal) throw ZipImportException(ZipFailure.LIMIT)
                declaredTotal += entry.size
                if (entry.size > 1024 * 1024 && entry.size > maxOf(1L, entry.compressedSize) * limits.compressionRatio) throw ZipImportException(ZipFailure.LIMIT)
                val name = path.substringAfterLast('/')
                if (name.substringAfterLast('.', "").lowercase() !in formats) {
                    skipped++
                    continue
                }
                if (++budget.books > limits.books) throw ZipImportException(ZipFailure.LIMIT)
                // Flatten into unique numbered directories: entries never determine parent paths.
                val destination = File(directory, "${budget.entries}/$name")
                if (!destination.canonicalPath.startsWith(directory.canonicalPath + File.separator)) throw ZipImportException(ZipFailure.UNSAFE)
                check(destination.parentFile!!.mkdirs())
                val crc = CRC32()
                var size = 0L
                zip.getInputStream(entry).use { source ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            checkCancelled()
                            val count = source.read(buffer)
                            if (count < 0) break
                            size += count
                            budget.bytes += count
                            if (size > limits.fileBytes || budget.bytes > limits.totalBytes) throw ZipImportException(ZipFailure.LIMIT)
                            crc.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (size != entry.size || crc.value != entry.crc) throw ZipImportException(ZipFailure.INVALID)
                books += destination
            }
        }
        if (books.isEmpty()) throw ZipImportException(ZipFailure.EMPTY, skipped)
        complete = true
        return ExtractedZip(books, skipped)
    } catch (error: IOException) {
        throw (error as? ZipImportException ?: ZipImportException(ZipFailure.INVALID))
    } catch (error: IllegalArgumentException) {
        throw ZipImportException(ZipFailure.INVALID)
    } finally {
        if (created) {
            archive.delete()
            if (!complete) directory.deleteRecursively()
        }
    }
}

// Bound the central directory BEFORE ZipFile allocates its entry index. ZIP64/multi-disk
// archives are unnecessary within our limits and deliberately rejected.
private fun validateZipDirectory(archive: File, budget: ZipBudget, checkCancelled: () -> Unit): Charset = RandomAccessFile(archive, "r").use { file ->
    val tail = ByteArray(minOf(file.length(), 65_557L).toInt())
    file.seek(file.length() - tail.size)
    file.readFully(tail)
    val end = (tail.size - 22 downTo 0).firstOrNull {
        tail.u32(it) == 0x06054b50L && it + 22 + tail.u16(it + 20) == tail.size
    } ?: throw ZipImportException(ZipFailure.INVALID)
    if (tail.u16(end + 4) != 0 || tail.u16(end + 6) != 0 || tail.u16(end + 8) != tail.u16(end + 10)) throw ZipImportException(ZipFailure.INVALID)
    val count = tail.u16(end + 10)
    val size = tail.u32(end + 12)
    val offset = tail.u32(end + 16)
    if (count == 65_535 || size > 4L * 1024 * 1024 || count > budget.limits.entries - budget.entries) throw ZipImportException(ZipFailure.LIMIT)
    if (offset + size > file.length() - tail.size + end) throw ZipImportException(ZipFailure.INVALID)
    file.seek(offset)
    val header = ByteArray(46)
    var actual = 0
    var encoding = Charsets.UTF_8
    while (file.filePointer < offset + size) {
        checkCancelled()
        if (++actual > budget.limits.entries - budget.entries) throw ZipImportException(ZipFailure.LIMIT)
        file.readFully(header)
        if (header.u32(0) != 0x02014b50L || header.u16(8) and 1 != 0 || header.u16(34) != 0) throw ZipImportException(ZipFailure.INVALID)
        val next = file.filePointer + header.u16(28) + header.u16(30) + header.u16(32)
        if (next > offset + size) throw ZipImportException(ZipFailure.INVALID)
        val name = ByteArray(header.u16(28))
        file.readFully(name)
        try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(name))
        } catch (_: CharacterCodingException) {
            if (header.u16(8) and 0x800 != 0) throw ZipImportException(ZipFailure.INVALID)
            // Unflagged DOS Cyrillic names are common in older FB2 collections.
            encoding = Charset.forName("CP866")
        }
        file.seek(next)
    }
    if (actual != count || file.filePointer != offset + size) throw ZipImportException(ZipFailure.INVALID)
    encoding
}

private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
private fun ByteArray.u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
