package com.econverter.app

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.io.IOException

// Linux test adapter exercises real RAR data through system libarchive. The Android JNI
// adapter is exercised separately by instrumentation tests, not replaced in production.
internal class HostRarReader(file: File) : BookArchiveReader {
    private val api = Native.load("archive", Api::class.java)
    private val archive = api.archive_read_new()
    init {
        try {
            checkResult(api.archive_read_support_format_rar(archive))
            checkResult(api.archive_read_support_format_rar5(archive))
            checkResult(api.archive_read_open_filename(archive, file.absolutePath, 32768))
        } catch (error: Throwable) {
            api.archive_read_free(archive)
            throw error
        }
    }
    private fun checkResult(result: Int) {
        if (result < 0) throw IOException(api.archive_error_string(archive)?.getString(0, "UTF-8"))
    }
    override fun next(): ArchiveBook? {
        val result = PointerByReference()
        val status = api.archive_read_next_header(archive, result)
        if (status == 1) return null
        checkResult(status)
        val entry = result.value
        val type = api.archive_entry_filetype(entry)
        return ArchiveBook(
            api.archive_entry_pathname_utf8(entry)?.getString(0, "UTF-8") ?: throw IOException("No UTF-8 pathname"),
            api.archive_entry_size(entry),
            type == 0x4000,
            type !in setOf(0x4000, 0x8000) || api.archive_entry_hardlink(entry) != null || api.archive_entry_symlink(entry) != null,
            api.archive_entry_is_encrypted(entry) != 0,
        )
    }
    override fun read(buffer: ByteArray): Int {
        val count = api.archive_read_data(archive, buffer, buffer.size.toLong())
        checkResult(count.toInt())
        return count.toInt().takeIf { it != 0 } ?: -1
    }
    override fun close() {
        api.archive_read_free(archive)
    }

    internal interface Api : Library {
        fun archive_read_new(): Pointer
        fun archive_read_support_format_rar(archive: Pointer): Int
        fun archive_read_support_format_rar5(archive: Pointer): Int
        fun archive_read_open_filename(archive: Pointer, file: String, blockSize: Long): Int
        fun archive_read_next_header(archive: Pointer, entry: PointerByReference): Int
        fun archive_read_data(archive: Pointer, buffer: ByteArray, size: Long): Long
        fun archive_read_free(archive: Pointer): Int
        fun archive_error_string(archive: Pointer): Pointer?
        fun archive_entry_pathname_utf8(entry: Pointer): Pointer?
        fun archive_entry_size(entry: Pointer): Long
        fun archive_entry_filetype(entry: Pointer): Int
        fun archive_entry_hardlink(entry: Pointer): Pointer?
        fun archive_entry_symlink(entry: Pointer): Pointer?
        fun archive_entry_is_encrypted(entry: Pointer): Int
    }
}

internal fun testArchiveReader(file: File, extension: String): BookArchiveReader = if (extension == "rar") HostRarReader(file) else openBookArchive(file, extension)
