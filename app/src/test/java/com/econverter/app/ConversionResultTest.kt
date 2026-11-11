package com.econverter.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConversionResultTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun successWithoutReadableNonEmptyOutputsBecomesAnError() {
        for (files in listOf(emptyList(), listOf(File(temporary.root, "missing.epub")), listOf(temporary.newFile()))) {
            val result = checkedConversionResult(ConversionResult(StatusMessage(R.string.status_done, isSuccess = true), files), ConversionService.RUN)
            assertTrue(result.status.isError)
            assertFalse(result.status.isSuccess)
            assertEquals(R.string.status_missing_outputs, result.status.resourceId)
            assertTrue(result.files.isEmpty())
        }
    }

    @Test
    fun validOutputsSurviveValidationAndPartialDisappearanceIsReported() {
        val file = temporary.newFile().apply { writeText("EPUB data") }
        val original = ConversionResult(StatusMessage(R.string.status_done, isSuccess = true), listOf(file))
        assertEquals(original, checkedConversionResult(original, ConversionService.RUN))
        val partial = checkedConversionResult(original.copy(files = listOf(file, File(temporary.root, "missing.epub"))), ConversionService.RUN)
        assertTrue(partial.status.isError)
        assertEquals(listOf(file), partial.files)
    }

    @Test
    fun metadataReadAndCancellationDoNotRequireOutputFiles() {
        val metadata = ConversionResult(StatusMessage(), metadata = BookMetadata("Title", emptyList(), "", ""))
        assertEquals(metadata, checkedConversionResult(metadata, ConversionService.READ_METADATA))
        val cancelled = ConversionResult(StatusMessage(R.string.status_cancelled))
        assertEquals(cancelled, checkedConversionResult(cancelled, ConversionService.RUN))
    }
}
