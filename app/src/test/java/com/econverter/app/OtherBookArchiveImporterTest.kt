package com.econverter.app

import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32

class OtherBookArchiveImporterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun realRar4Rar5AndSolidDataDecodeCorrectly() {
        assertEquals("<FictionBook>one</FictionBook>", extract(fixture("rar4-books.rar"), "rar").books.single().readText())
        val unicode = extract(fixture("rar5-books.rar"), "rar")
        assertEquals(listOf("Книга.fb2", "Книга.fb2"), unicode.books.map { it.name })
        assertEquals(listOf("<FictionBook>first</FictionBook>", "<FictionBook>second</FictionBook>"), unicode.books.map { it.readText() })
        assertTrue(extract(fixture("rar4-compressed.rar"), "rar").books.single().length() > 0)
        assertEquals(1200, extract(fixture("rar5.rar"), "rar", formats = setOf("bin")).books.single().length())
        assertEquals(4, extract(fixture("rar5-solid.rar"), "rar", formats = setOf("bin")).books.size)
    }

    @Test
    fun sevenZHandlesNestedUnicodeMultipleBooksAndSkipsOtherArchives() {
        val bytes = sevenZ(temporary.newFile(), "Папка/Книга.fb2" to "one", "other/Книга.fb2" to "two", "nested.zip" to "ignored", "cover.jpg" to "image")
        val result = extract(bytes, "7z")
        assertEquals(listOf("Книга.fb2", "Книга.fb2"), result.books.map { it.name })
        assertEquals(listOf("one", "two"), result.books.map { it.readText() })
        assertEquals(2, result.skipped)
    }

    @Test
    fun archivesCannotEscapeOrCreateLinks() {
        for (name in listOf("../escape.fb2", "/escape.fb2", "C:/escape.fb2", "..\\escape.fb2")) {
            reject(sevenZ(temporary.newFile(), "safe.fb2" to "safe", name to "bad"), "7z", ArchiveFailure.UNSAFE)
        }
        reject(fixture("rar4.rar"), "rar", ArchiveFailure.UNSAFE)
        val link = temporary.newFile()
        SevenZOutputFile(link).use { archive ->
            archive.putArchiveEntry(
                SevenZArchiveEntry().apply {
                    name = "link.fb2"
                    hasWindowsAttributes = true
                    windowsAttributes = (0xa1ff shl 16) or 0x8000
                },
            )
            archive.write("../other".toByteArray())
            archive.closeArchiveEntry()
        }
        reject(link.readBytes(), "7z", ArchiveFailure.UNSAFE)
        assertFalse(File(temporary.root, "escape.fb2").exists())
    }

    @Test
    fun corruptEncryptedMultiVolumeEmptyAndRenamedOtherFormatsAreRejected() {
        for (name in listOf("rar4-volume.rar", "rar5-volume.rar", "rar5-encrypted.rar")) reject(fixture(name), "rar", ArchiveFailure.INVALID)
        reject(fixture("rar4-books.rar").copyOf(25), "rar", ArchiveFailure.INVALID)
        reject(fixture("rar5-books.rar").copyOf(25), "rar", ArchiveFailure.INVALID)
        val bytes = sevenZ(temporary.newFile(), "book.fb2" to "book")
        reject(bytes.copyOf(25), "7z", ArchiveFailure.INVALID)
        reject(fixture("encrypted.7z"), "7z", ArchiveFailure.INVALID)
        assertEquals(2, extract(fixture("books.7z"), "7z").books.size)
        reject(sevenZ(temporary.newFile()), "7z", ArchiveFailure.EMPTY)
        reject("ustar".toByteArray(), "rar", ArchiveFailure.INVALID)
        reject(byteArrayOf(0x1f, 0x8b.toByte()), "7z", ArchiveFailure.INVALID)
    }

    @Test
    fun sizeEntryBookArchiveAndSharedBudgetsAreEnforced() {
        val rar = fixture("rar5-books.rar")
        reject(rar, "rar", ArchiveFailure.LIMIT, ZipBudget(ZipLimits(archiveBytes = 8)))
        reject(rar, "rar", ArchiveFailure.LIMIT, ZipBudget(ZipLimits(fileBytes = 8)))
        reject(rar, "rar", ArchiveFailure.LIMIT, ZipBudget(ZipLimits(entries = 1)))
        reject(rar, "rar", ArchiveFailure.LIMIT, ZipBudget(ZipLimits(books = 1)))
        reject(rar, "rar", ArchiveFailure.LIMIT, ZipBudget().apply { archives = 64 })
        reject(rar, "rar", ArchiveFailure.LIMIT, ZipBudget(bytes = ZipLimits().totalBytes - 1))
        val budget = ZipBudget(books = 255)
        extract(fixture("rar4-books.rar"), "rar", budget)
        reject(sevenZ(temporary.newFile(), "book.fb2" to "book"), "7z", ArchiveFailure.LIMIT, budget)
    }

    @Test
    fun sevenZDictionaryAndEncodedHeaderAllocationAreBounded() {
        val bytes = sevenZ(temporary.newFile(), "book.fb2" to "book")
        val header = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val offset = 32 + header.getLong(12).toInt()
        val size = header.getLong(20).toInt()
        val coder = (offset until bytes.size - 4).first { bytes[it] == 0x21.toByte() && bytes[it + 1] == 0x21.toByte() && bytes[it + 2] == 1.toByte() }
        bytes[coder + 3] = 36 // LZMA2 dictionary requiring gigabytes must fail before allocation.
        header.putInt(28, CRC32().apply { update(bytes, offset, size) }.value.toInt())
        header.putInt(8, CRC32().apply { update(bytes, 12, 20) }.value.toInt())
        reject(bytes, "7z", ArchiveFailure.LIMIT)

        val encoded = byteArrayOf(0x17, 6, 0, 1, 9, 1, 0, 7, 11, 1, 0, 1, 0x21, 0x21, 1, 1, 12, 0xf0.toByte(), 0, 0, 0, 0x40, 0, 0)
        val bomb = ByteArray(32) + encoded
        byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c).copyInto(bomb)
        java.nio.ByteBuffer.wrap(bomb).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(20, encoded.size.toLong())
        reject(bomb, "7z", ArchiveFailure.LIMIT)
    }

    @Test
    fun cancellationRollsBackExtractedData() {
        val directory = freshDirectory()
        var checks = 0
        try {
            extractOtherArchiveBooks(ByteArrayInputStream(fixture("rar5-books.rar")), directory, "rar", setOf("fb2"), ZipBudget(), ::testArchiveReader) {
                if (++checks == 5) throw CancellationException("Cancelled")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(directory.exists())
        }
    }

    private fun freshDirectory() = File(temporary.root, UUID.randomUUID().toString())
    private fun extract(bytes: ByteArray, extension: String, budget: ZipBudget = ZipBudget(), formats: Set<String> = ConversionBackend.AUTO.inputFormats): ExtractedZip = extractOtherArchiveBooks(ByteArrayInputStream(bytes), freshDirectory(), extension, formats, budget, ::testArchiveReader)

    private fun reject(bytes: ByteArray, extension: String, reason: ArchiveFailure, budget: ZipBudget = ZipBudget()) {
        val directory = freshDirectory()
        try {
            extractOtherArchiveBooks(ByteArrayInputStream(bytes), directory, extension, setOf("fb2", "txt"), budget, ::testArchiveReader)
            fail("Expected $reason")
        } catch (error: ArchiveImportException) {
            assertEquals(reason, error.reason)
            assertFalse("Rejected archive left temporary data", directory.exists())
        }
    }
}

internal fun fixture(name: String): ByteArray = requireNotNull(OtherBookArchiveImporterTest::class.java.getResourceAsStream("/archives/$name")).use { it.readBytes() }
internal fun sevenZ(file: File, vararg entries: Pair<String, String>): ByteArray {
    SevenZOutputFile(file).use { archive ->
        for ((name, text) in entries) {
            val data = text.toByteArray()
            archive.putArchiveEntry(
                SevenZArchiveEntry().apply {
                    this.name = name
                    size = data.size.toLong()
                },
            )
            archive.write(data)
            archive.closeArchiveEntry()
        }
    }
    return file.readBytes()
}
