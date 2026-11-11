package com.econverter.app

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipBookImporterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun nestedCyrillicNamesAndDuplicateBasenamesUseSeparateFiles() {
        val result = extract(zip("Папка/Книга.FB2" to "first", "another/Книга.FB2" to "second"))
        assertEquals(listOf("Книга.FB2", "Книга.FB2"), result.books.map { it.name })
        assertEquals(listOf("first", "second"), result.books.map { it.readText() })
        assertNotEquals(result.books[0], result.books[1])
        assertFalse(File(result.books.first().parentFile!!.parentFile, "source.zip").exists())
    }

    @Test
    fun legacyDosCyrillicNamesAreSupported() {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer, Charset.forName("CP866")).use {
            it.putNextEntry(ZipEntry("Папка/Книга.fb2"))
            it.write("book".toByteArray())
            it.closeEntry()
        }
        assertEquals("Книга.fb2", extract(buffer.toByteArray()).books.single().name)
    }

    @Test
    fun allBackendFormatsAreAcceptedButNestedArchivesAreNotExpanded() {
        val entries = ConversionBackend.AUTO.inputFormats.map { "book.$it" to "book" } + listOf("image.jpg" to "image", "nested.zip" to "archive")
        val result = extract(zip(*entries.toTypedArray()))
        assertEquals(ConversionBackend.AUTO.inputFormats, result.books.map { it.extension }.toSet())
        assertEquals(2, result.skipped)
        assertEquals(1, extract(zip("first.fb2" to "book")).books.size)
        val boko = extract(zip("first.fb2" to "book", "second.epub" to "epub"), formats = ConversionBackend.BOKO.inputFormats)
        assertEquals(listOf("second.epub"), boko.books.map { it.name })
        assertEquals(1, boko.skipped)
    }

    @Test
    fun invalidTruncatedEmptyAndUnsupportedArchivesAreRejectedAndRemoved() {
        reject(byteArrayOf(1, 2, 3), ZipFailure.INVALID)
        reject(zip("book.fb2" to "data").dropLast(10).toByteArray(), ZipFailure.INVALID)
        reject(zip(), ZipFailure.EMPTY)
        reject(zip("image.jpg" to "data"), ZipFailure.EMPTY)
    }

    @Test
    fun zipSlipIsRejectedEvenForUnsupportedEntriesAndRollsBackEarlierBooks() {
        for (path in listOf("../escape.fb2", "/escape.fb2", "C:/escape.fb2", "dir/../../escape.jpg", "..\\escape.fb2")) {
            reject(zip("valid.fb2" to "first", path to "evil"), ZipFailure.UNSAFE)
        }
        assertFalse(File(temporary.root, "escape.fb2").exists())
    }

    @Test
    fun encryptionAndCrcCorruptionAreRejected() {
        val encrypted = zip("book.fb2" to "payload")
        val central = signature(encrypted, 0x02014b50)
        encrypted[6] = (encrypted[6].toInt() or 1).toByte()
        encrypted[central + 8] = (encrypted[central + 8].toInt() or 1).toByte()
        reject(encrypted, ZipFailure.INVALID)
        val corrupted = zip("book.fb2" to "payload", stored = true)
        val payload = 30 + "book.fb2".toByteArray().size
        corrupted[payload] = (corrupted[payload].toInt() xor 1).toByte()
        reject(corrupted, ZipFailure.INVALID)
    }

    @Test
    fun compressedFileTotalEntryBookAndRatioLimitsAreEnforced() {
        reject(zip("book.fb2" to "four"), ZipFailure.LIMIT, ZipLimits(archiveBytes = 8))
        reject(zip("book.fb2" to "four"), ZipFailure.LIMIT, ZipLimits(fileBytes = 3))
        reject(zip("a.fb2" to "four", "b.fb2" to "four"), ZipFailure.LIMIT, ZipLimits(totalBytes = 7))
        reject(zip("folder/" to "", "book.fb2" to "book"), ZipFailure.LIMIT, ZipLimits(entries = 1))
        reject(zip("a.fb2" to "a", "b.fb2" to "b"), ZipFailure.LIMIT, ZipLimits(books = 1))
        reject(zip("bomb.fb2" to "x".repeat(2 * 1024 * 1024)), ZipFailure.LIMIT)
    }

    @Test
    fun actualInflatedBytesAreLimitedEvenWhenCentralDirectoryLies() {
        val bytes = zip("book.fb2" to "1234567890")
        val central = signature(bytes, 0x02014b50)
        for (i in 0..3) bytes[central + 24 + i] = 0
        reject(bytes, ZipFailure.LIMIT, ZipLimits(fileBytes = 5))
    }

    @Test
    fun forgedCentralCountCannotBypassEntryLimits() {
        val bytes = zip("a.fb2" to "a", "b.fb2" to "b")
        val end = signature(bytes, 0x06054b50)
        bytes[end + 8] = 1
        bytes[end + 10] = 1
        reject(bytes, ZipFailure.LIMIT, ZipLimits(entries = 1))
    }

    @Test
    fun centralDirectoryMemoryAndArchiveCountAreBoundedBeforeIndexing() {
        val bytes = zip("a.fb2" to "a")
        val end = signature(bytes, 0x06054b50)
        val tooLarge = 4 * 1024 * 1024 + 1
        for (i in 0..3) bytes[end + 12 + i] = (tooLarge ushr (8 * i)).toByte()
        reject(bytes, ZipFailure.LIMIT)
        val budget = ZipBudget().apply { archives = 64 }
        val directory = freshDirectory()
        try {
            extractZipBooks(ByteArrayInputStream(zip("a.fb2" to "a")), directory, setOf("fb2"), budget)
            fail("Expected archive limit")
        } catch (error: ZipImportException) {
            assertEquals(ZipFailure.LIMIT, error.reason)
            assertFalse(directory.exists())
        }
    }

    @Test
    fun budgetIsSharedBetweenArchivesAndExistingSelection() {
        val budget = ZipBudget(ZipLimits(fileBytes = 10, totalBytes = 512), books = 255)
        extract(zip("a.fb2" to "a"), budget = budget)
        val directory = freshDirectory()
        try {
            extractZipBooks(ByteArrayInputStream(zip("b.fb2" to "b")), directory, setOf("fb2"), budget)
            fail("Expected a shared book limit")
        } catch (error: ZipImportException) {
            assertEquals(ZipFailure.LIMIT, error.reason)
            assertFalse(directory.exists())
        }
        val almostFull = ZipBudget(bytes = ZipLimits().totalBytes - 1)
        val second = freshDirectory()
        try {
            extractZipBooks(ByteArrayInputStream(zip("b.fb2" to "book")), second, setOf("fb2"), almostFull)
            fail("Expected a shared byte limit")
        } catch (error: ZipImportException) {
            assertEquals(ZipFailure.LIMIT, error.reason)
            assertFalse(second.exists())
        }
    }

    @Test
    fun cancellationDeletesPartiallyExtractedFiles() {
        val directory = freshDirectory()
        var checks = 0
        try {
            extractZipBooks(ByteArrayInputStream(zip("book.fb2" to "book")), directory, setOf("fb2"), ZipBudget()) {
                if (++checks == 5) throw CancellationException("Cancelled")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(directory.exists())
        }
    }

    private fun freshDirectory() = File(temporary.root, UUID.randomUUID().toString())

    private fun extract(bytes: ByteArray, formats: Set<String> = ConversionBackend.AUTO.inputFormats, budget: ZipBudget = ZipBudget()): ExtractedZip = extractZipBooks(ByteArrayInputStream(bytes), freshDirectory(), formats, budget)

    private fun reject(bytes: ByteArray, reason: ZipFailure, limits: ZipLimits = ZipLimits()) {
        val directory = freshDirectory()
        try {
            extractZipBooks(ByteArrayInputStream(bytes), directory, setOf("fb2"), ZipBudget(limits))
            fail("Expected $reason")
        } catch (error: ZipImportException) {
            assertEquals(reason, error.reason)
            assertFalse("Rejected ZIP left temporary files", directory.exists())
        }
    }

    private fun signature(bytes: ByteArray, value: Int): Int = (0..bytes.size - 4).first { offset ->
        (0..3).all { bytes[offset + it] == (value ushr (8 * it)).toByte() }
    }

    private fun zip(vararg entries: Pair<String, String>, stored: Boolean = false): ByteArray = ByteArrayOutputStream().use { buffer ->
        ZipOutputStream(buffer).use { zip ->
            for ((name, text) in entries) {
                val bytes = text.toByteArray()
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        if (stored) {
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = size
                            crc = CRC32().apply { update(bytes) }.value
                        }
                    },
                )
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        buffer.toByteArray()
    }
}
