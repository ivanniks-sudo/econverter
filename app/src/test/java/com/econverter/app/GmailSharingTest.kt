package com.econverter.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@Suppress("DEPRECATION")
class GmailSharingTest {
    @Test
    fun selectedSingleAndMultipleFilesOpenComposeDirectlyWithReadGrants() {
        withBooks { context, _, books ->
            for (count in 1..2) {
                val vm = ConverterViewModel()
                vm.inputUris = books.take(count).map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
                vm.inputFileNames = books.take(count).map { it.name }
                val launch = RecordingContext(context)
                vm.openGmail(launch)
                val intent = requireNotNull(launch.launched)
                assertAttachments(context, intent, books.take(count).map { it.readText() })
                assertEquals("application/epub+zip", intent.type)
                assertEquals(count, vm.inputUris.size)
                assertFalse(vm.pendingSave)
            }
        }
    }

    @Test
    fun mixedSelectedFileTypesUseMultipleSendWithAllAttachments() {
        withBooks { context, directory, books ->
            val pdf = File(directory, "book.pdf").apply { writeText("pdf data") }
            val files = listOf(books.first(), pdf)
            val vm = ConverterViewModel()
            vm.inputUris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
            val intent = requireNotNull(vm.createGmailIntent(context))
            assertEquals("*/*", intent.type)
            assertAttachments(context, intent, files.map { it.readText() })
        }
    }

    @Test
    fun convertedFilesTakePriorityAndRemainReadableAfterDismissal() {
        withBooks { context, directory, books ->
            for (count in 1..2) {
                val vm = ConverterViewModel()
                vm.inputUris = listOf(Uri.parse("content://original/input.pdf"))
                vm.inputFileNames = listOf("input.pdf")
                vm.outputFiles = books.take(count)
                vm.internalWorkDirectory = directory
                vm.pendingSave = true
                val intent = requireNotNull(vm.createGmailIntent(context))
                assertAttachments(context, intent, books.take(count).map { it.readText() })
                assertNull(vm.internalWorkDirectory)
                assertTrue(vm.pendingSave)
                // Existing Share keeps its normal chooser and is still available after Gmail.
                val chooser = requireNotNull(vm.createShareIntent(context))
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                assertNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.getPackage())
                vm.discardPendingSave()
                assertAttachments(context, intent, books.take(count).map { it.readText() })
                assertEquals(listOf("input.pdf"), vm.inputFileNames)
            }
        }
    }

    @Test
    fun missingOutputsDoNotLaunchAnEmptyDraftOrFallBackToOriginalInputs() {
        withBooks { context, directory, books ->
            val vm = ConverterViewModel()
            vm.inputUris = listOf(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", books.first()))
            vm.outputFiles = listOf(books.last())
            vm.internalWorkDirectory = directory
            vm.pendingSave = true
            books.last().delete()
            val launch = RecordingContext(context)
            vm.openGmail(launch)
            assertNull(launch.launched)
            assertNull(vm.createShareIntent(context))
            assertEquals(R.string.status_missing_outputs, vm.status.resourceId)
            assertTrue(vm.status.isError)
            assertTrue(books.first().isFile)
        }
    }

    @Test
    fun unavailableGmailIsReportedWithoutDiscardingOutputs() {
        withBooks { context, directory, books ->
            val vm = ConverterViewModel()
            vm.outputFiles = books
            vm.internalWorkDirectory = directory
            vm.pendingSave = true
            val missing = object : ContextWrapper(context) {
                override fun startActivity(intent: Intent): Unit = throw ActivityNotFoundException("Gmail is absent")
            }
            vm.openGmail(missing)
            assertEquals(StatusMessage(R.string.status_gmail_unavailable, isError = true), vm.status)
            assertTrue(vm.pendingSave)
            assertTrue(books.all(File::isFile))
            assertNotNull(vm.createShareIntent(context))
        }
    }

    @Test
    fun attachmentPermissionErrorsAreReportedWithoutChangingSelection() {
        withBooks { context, _, books ->
            val vm = ConverterViewModel()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", books.first())
            vm.inputUris = listOf(uri)
            val denied = object : ContextWrapper(context) {
                override fun startActivity(intent: Intent): Unit = throw SecurityException("Cannot grant URI")
            }
            vm.openGmail(denied)
            assertEquals(StatusMessage(R.string.status_gmail_failed, isError = true), vm.status)
            assertEquals(listOf(uri), vm.inputUris)
        }
    }

    @Test
    fun emptyBusyAndNonContentSelectionsCannotLaunchGmail() {
        val context = RuntimeEnvironment.getApplication()
        val vm = ConverterViewModel()
        val launch = RecordingContext(context)
        assertNull(vm.createGmailIntent(context))
        vm.inputUris = listOf(Uri.parse("content://books/one.epub"))
        vm.isConverting = true
        vm.openGmail(launch)
        assertNull(launch.launched)
        vm.isConverting = false
        vm.isSaving = true
        assertNull(vm.createGmailIntent(context))
        vm.isSaving = false
        vm.inputUris = listOf(Uri.parse("file:///private/book.epub"))
        vm.openGmail(launch)
        assertNull(launch.launched)
        assertEquals(R.string.status_gmail_failed, vm.status.resourceId)
    }

    private fun assertAttachments(context: Context, intent: Intent, contents: List<String>) {
        assertEquals("com.google.android.gm", intent.getPackage())
        assertEquals(if (contents.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNull(intent.component)
        for (extra in listOf(Intent.EXTRA_EMAIL, Intent.EXTRA_CC, Intent.EXTRA_BCC, Intent.EXTRA_SUBJECT, Intent.EXTRA_TEXT, Intent.EXTRA_HTML_TEXT, Intent.EXTRA_INTENT)) {
            assertFalse("Unexpected prefilled field $extra", intent.hasExtra(extra))
        }
        val uris = if (contents.size == 1) listOf(requireNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))) else requireNotNull(intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(uris.all { it.scheme == "content" })
        assertEquals(uris, (0 until requireNotNull(intent.clipData).itemCount).map { intent.clipData!!.getItemAt(it).uri })
        assertEquals(contents, uris.map { context.contentResolver.openInputStream(it)!!.bufferedReader().use { input -> input.readText() } })
    }

    private fun withBooks(test: (Context, File, List<File>) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        // Robolectric's MIME registry starts empty instead of using Android's built-in mappings.
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("epub", "application/epub+zip")
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("pdf", "application/pdf")
        // Robolectric creates a new filesDir for each test; attachInfo refreshes FileProvider's cached roots.
        val authority = "${context.packageName}.fileprovider"
        val provider = FileProvider()
        provider.attachInfo(context, requireNotNull(context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)))
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val directory = File(context.filesDir, "conversion-runs/gmail-${UUID.randomUUID()}").apply { mkdirs() }
        val books = listOf("first.epub", "second.epub").map { name -> File(directory, name).apply { writeText(name) } }
        try {
            test(context, directory, books)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class RecordingContext(context: Context) : ContextWrapper(context) {
        var launched: Intent? = null
        override fun startActivity(intent: Intent) {
            launched = intent
        }
    }
}
