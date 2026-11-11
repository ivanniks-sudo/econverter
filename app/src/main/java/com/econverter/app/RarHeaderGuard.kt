package com.econverter.app

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

// libarchive can report EOF for some truncated files. Validate framing, header CRCs and
// the end marker ourselves, and reject split/encrypted archives before native decoding.
internal fun validateRarHeader(file: File, checkCancelled: () -> Unit = {}) {
    RandomAccessFile(file, "r").use { input ->
        input.seek(6)
        val version = input.readUnsignedByte()
        input.seek(if (version == 0) 7 else 8)
        var entries = 0
        var ended = false
        while (input.filePointer < input.length()) {
            checkCancelled()
            if (version == 0) {
                val start = input.filePointer
                val prefix = ByteArray(7).also(input::readFully)
                val type = prefix[2].toInt() and 255
                val flags = prefix.little16(3)
                val size = prefix.little16(5)
                if (size < 7 || size > input.length() - start) invalidRar()
                val header = prefix + ByteArray(size - 7).also(input::readFully)
                if (CRC32().apply { update(header, 2, size - 2) }.value.toInt() and 65535 != prefix.little16(0)) invalidRar()
                if (type == 0x73 && flags and (1 or 0x80) != 0) invalidRar()
                if (type == 0x74 && flags and 7 != 0) invalidRar()
                if (type == 0x74 && ++entries > 2048) throw ArchiveImportException(ArchiveFailure.LIMIT)
                val packed = if (flags and 0x8000 != 0) header.little32(7) else 0L
                if (type == 0x74 && flags and 0x100 != 0) {
                    if (size < 40) invalidRar()
                    val high = header.little32(32)
                    if (high != 0L) throw ArchiveImportException(ArchiveFailure.LIMIT)
                }
                if (packed > input.length() - input.filePointer) invalidRar()
                input.seek(input.filePointer + packed)
                if (type == 0x7b) {
                    ended = true
                    break
                }
            } else {
                val start = input.filePointer
                val expected = java.lang.Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                val size = input.rarNumber()
                if (size > 2 * 1024 * 1024) throw ArchiveImportException(ArchiveFailure.LIMIT)
                val payloadOffset = input.filePointer
                if (size > input.length() - payloadOffset) invalidRar()
                val headerSize = (payloadOffset - start - 4 + size).toInt()
                input.seek(start + 4)
                val header = ByteArray(headerSize).also(input::readFully)
                if (CRC32().apply { update(header) }.value != expected) invalidRar()
                val payload = ByteArrayInputStream(header, (payloadOffset - start - 4).toInt(), size.toInt())
                val type = payload.rarNumber()
                val flags = payload.rarNumber()
                if (flags and 1L != 0L) payload.rarNumber()
                val packed = if (flags and 2L != 0L) payload.rarNumber() else 0L
                if (type == 4L || flags and 0x18L != 0L) invalidRar()
                if (type == 1L && payload.rarNumber() and 1L != 0L) invalidRar()
                if (type == 2L && ++entries > 2048) throw ArchiveImportException(ArchiveFailure.LIMIT)
                if (packed > input.length() - input.filePointer) invalidRar()
                input.seek(input.filePointer + packed)
                if (type == 5L) {
                    if (payload.rarNumber() and 1L != 0L) invalidRar()
                    ended = true
                    break
                }
            }
        }
        if (!ended || input.filePointer != input.length()) invalidRar()
    }
}

private fun invalidRar(): Nothing = throw ArchiveImportException(ArchiveFailure.INVALID)
private fun ByteArray.little16(offset: Int): Int {
    if (offset + 2 > size) invalidRar()
    return (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
}
private fun ByteArray.little32(offset: Int): Long = little16(offset).toLong() or (little16(offset + 2).toLong() shl 16)
private fun RandomAccessFile.rarNumber(): Long = readRarNumber { readUnsignedByte() }
private fun InputStream.rarNumber(): Long = readRarNumber { read().also { if (it < 0) invalidRar() } }
private fun readRarNumber(next: () -> Int): Long {
    var value = 0L
    for (shift in 0..56 step 7) {
        val byte = next()
        value = value or ((byte and 127).toLong() shl shift)
        if (byte and 128 == 0) return value
    }
    invalidRar()
}
