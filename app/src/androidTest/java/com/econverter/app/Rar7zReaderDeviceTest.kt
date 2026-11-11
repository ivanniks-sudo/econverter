package com.econverter.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises the shipped Android native decoder, unlike host unit tests using system libarchive. */
@RunWith(AndroidJUnit4::class)
class Rar7zReaderDeviceTest {
    @Test
    @ConversionSmokeTest
    fun shippedRar4Rar5SolidAndSevenZReadersExtractBooks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cases = mapOf("rar4-books.rar" to 1, "rar4-compressed.rar" to 1, "rar5-books.rar" to 2, "rar5.rar" to 1, "rar5-solid.rar" to 4, "books.7z" to 2)
        for ((name, count) in cases) {
            val directory = File(instrumentation.targetContext.cacheDir, "archive-device-${UUID.randomUUID()}")
            try {
                val result = instrumentation.context.assets.open("archives/$name").use {
                    extractOtherArchiveBooks(it, directory, name.substringAfterLast('.'), setOf("fb2", "txt", "bin"), ZipBudget())
                }
                assertEquals(name, count, result.books.size)
                assertTrue(result.books.all { it.isFile })
                if (name == "rar5-books.rar") assertEquals(listOf("Книга.fb2", "Книга.fb2"), result.books.map { it.name })
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
