package com.econverter.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Commons Compress bounds decoder dictionaries, but not the initial next-header allocation
// or the decoded header's unpack size. Check both BEFORE constructing SevenZFile.
internal fun validateSevenZHeader(file: File) {
    RandomAccessFile(file, "r").use { input ->
        if (input.length() < 32) throw ArchiveImportException(ArchiveFailure.INVALID)
        input.seek(12)
        val offset = java.lang.Long.reverseBytes(input.readLong())
        val size = java.lang.Long.reverseBytes(input.readLong())
        if (offset < 0 || size < 0 || offset > input.length() - 32 || size > input.length() - 32 - offset) throw ArchiveImportException(ArchiveFailure.INVALID)
        if (size > 4 * 1024 * 1024) throw ArchiveImportException(ArchiveFailure.LIMIT)
        if (size == 0L) return
        input.seek(32 + offset)
        val bytes = ByteArray(size.toInt()).also(input::readFully)
        val header = SevenZHeaderCursor(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        if (header.byte() != 0x17) return // Unencoded header is already bounded by the check above.
        header.expect(6) // PackInfo
        header.number() // Packed-data position
        val streams = header.number()
        if (streams != 1L) throw ArchiveImportException(ArchiveFailure.INVALID)
        header.expect(9)
        header.number() // Packed size
        var next = header.byte()
        if (next == 10) {
            header.crc()
            next = header.byte()
        }
        if (next != 0) throw ArchiveImportException(ArchiveFailure.INVALID)
        header.expect(7) // UnpackInfo
        header.expect(11) // Folder
        if (header.number() != 1L || header.byte() != 0) throw ArchiveImportException(ArchiveFailure.INVALID)
        val coders = header.number()
        if (coders !in 1..16) throw ArchiveImportException(ArchiveFailure.LIMIT)
        repeat(coders.toInt()) {
            val flags = header.byte()
            if (flags and 0x80 != 0 || flags and 0xf == 0) throw ArchiveImportException(ArchiveFailure.INVALID)
            header.skip(flags and 0xf)
            if (flags and 0x10 != 0 && (header.number() != 1L || header.number() != 1L)) throw ArchiveImportException(ArchiveFailure.INVALID)
            if (flags and 0x20 != 0) {
                val properties = header.number()
                if (properties > 64) throw ArchiveImportException(ArchiveFailure.LIMIT)
                header.skip(properties.toInt())
            }
        }
        repeat(coders.toInt() - 1) {
            header.number()
            header.number()
        }
        header.expect(12) // CodersUnpackSize: every stage of the encoded header must be small.
        repeat(coders.toInt()) { if (header.number() > 4 * 1024 * 1024) throw ArchiveImportException(ArchiveFailure.LIMIT) }
        next = header.byte()
        if (next == 10) {
            header.crc()
            next = header.byte()
        }
        if (next != 0 || header.byte() != 0) throw ArchiveImportException(ArchiveFailure.INVALID)
    }
}

private class SevenZHeaderCursor(private val data: ByteBuffer) {
    fun byte(): Int {
        if (!data.hasRemaining()) throw ArchiveImportException(ArchiveFailure.INVALID)
        return data.get().toInt() and 255
    }
    fun expect(value: Int) {
        if (byte() != value) throw ArchiveImportException(ArchiveFailure.INVALID)
    }
    fun skip(count: Int) {
        if (count < 0 || count > data.remaining()) throw ArchiveImportException(ArchiveFailure.INVALID)
        data.position(data.position() + count)
    }
    fun number(): Long {
        val first = byte()
        var mask = 0x80
        var value = 0L
        for (i in 0..7) {
            if (first and mask == 0) return value or ((first and (mask - 1)).toLong() shl (8 * i))
            value = value or (byte().toLong() shl (8 * i))
            mask = mask ushr 1
        }
        if (value < 0) throw ArchiveImportException(ArchiveFailure.INVALID)
        return value
    }
    fun crc() {
        val all = byte()
        val present = if (all != 0) true else byte() and 0x80 != 0
        if (present) skip(4)
    }
}
