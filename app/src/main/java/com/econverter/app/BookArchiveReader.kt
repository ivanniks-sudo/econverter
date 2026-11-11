package com.econverter.app

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

internal data class ArchiveBook(val name: String, val size: Long, val directory: Boolean = false, val link: Boolean = false, val encrypted: Boolean = false)
internal interface BookArchiveReader : Closeable {
    fun next(): ArchiveBook?
    fun read(buffer: ByteArray): Int
}

internal fun openBookArchive(file: File, extension: String): BookArchiveReader = when (extension) {
    "rar" -> NativeRarReader(file)
    "7z" -> SevenZBookReader(file)
    else -> throw ArchiveImportException(ArchiveFailure.INVALID)
}

private class SevenZBookReader(file: File) : BookArchiveReader {
    private val channel = run {
        validateSevenZHeader(file)
        RandomAccessChannel(file)
    }
    private val zip = try {
        SevenZFile.builder().setSeekableByteChannel(channel).setMaxMemoryLimitKiB(64 * 1024)
            .setTryToRecoverBrokenArchives(false).get()
    } catch (error: Throwable) {
        channel.close()
        throw error
    }

    override fun next(): ArchiveBook? = zip.nextEntry?.let {
        if (it.isAntiItem) throw ArchiveImportException(ArchiveFailure.INVALID)
        val mode = (it.windowsAttributes ushr 16) and 0xf000
        val link = it.hasWindowsAttributes && (it.windowsAttributes and 0x400 != 0 || (mode != 0 && mode !in setOf(0x4000, 0x8000)))
        ArchiveBook(it.name ?: throw ArchiveImportException(ArchiveFailure.INVALID), it.size, it.isDirectory, link)
    }
    override fun read(buffer: ByteArray): Int = zip.read(buffer)
    override fun close() = zip.close()
}

// Avoid java.nio.file at the call site on Android 24/25. Desugaring covers dependency internals.
private class RandomAccessChannel(file: File) : SeekableByteChannel {
    private val input = RandomAccessFile(file, "r")
    private var open = true
    override fun read(buffer: ByteBuffer): Int {
        val bytes = ByteArray(minOf(buffer.remaining(), 32 * 1024))
        val count = input.read(bytes)
        if (count > 0) buffer.put(bytes, 0, count)
        return count
    }
    override fun position(): Long = input.filePointer
    override fun position(value: Long): SeekableByteChannel = apply {
        require(value >= 0)
        input.seek(value)
    }
    override fun size(): Long = input.length()
    override fun isOpen(): Boolean = open
    override fun close() {
        open = false
        input.close()
    }
    override fun truncate(size: Long): SeekableByteChannel = throw java.nio.channels.NonWritableChannelException()
    override fun write(buffer: ByteBuffer): Int = throw java.nio.channels.NonWritableChannelException()
}

private class NativeRarReader(file: File) : BookArchiveReader {
    private val archive = Archive.readNew()
    init {
        try {
            // Enable only RAR readers, never libarchive's generic format/filter auto-detection.
            Archive.readSupportFormatRar(archive)
            Archive.readSupportFormatRar5(archive)
            Archive.readOpenFileName(archive, file.absolutePath.toByteArray(Charsets.UTF_8), 32 * 1024L)
        } catch (error: Throwable) {
            Archive.readFree(archive)
            throw error
        }
    }
    override fun next(): ArchiveBook? {
        val entry = Archive.readNextHeader(archive)
        if (entry == 0L) return null
        return ArchiveBook(
            ArchiveEntry.pathnameUtf8(entry) ?: throw ArchiveImportException(ArchiveFailure.INVALID),
            ArchiveEntry.size(entry),
            ArchiveEntry.filetype(entry) == 0x4000,
            ArchiveEntry.filetype(entry) !in setOf(0x4000, 0x8000) || ArchiveEntry.hardlink(entry) != null || ArchiveEntry.symlink(entry) != null,
            ArchiveEntry.isEncrypted(entry),
        )
    }
    override fun read(buffer: ByteArray): Int {
        val bytes = ByteBuffer.wrap(buffer)
        Archive.readData(archive, bytes)
        return bytes.position().takeIf { it > 0 } ?: -1
    }
    override fun close() = Archive.readFree(archive)
}
