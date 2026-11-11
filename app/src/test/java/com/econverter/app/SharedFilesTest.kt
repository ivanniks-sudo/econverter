package com.econverter.app

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.BadParcelableException
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedFilesTest {
    private val first = Uri.parse("content://shared-books/1")
    private val second = Uri.parse("content://shared-books/2")

    @Test
    fun sendAndMultipleExtractStreamsAndDeduplicate() {
        assertEquals(listOf(first), sharedFileUris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first)))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, first))
        assertEquals(listOf(first, second), sharedFileUris(intent))
        assertEquals(emptyList<Uri>(), sharedFileUris(Intent(Intent.ACTION_MAIN).putExtra(Intent.EXTRA_STREAM, first)))
    }

    @Test
    @Config(sdk = [33])
    fun streamsWorkOnModernAndroidToo() {
        sendAndMultipleExtractStreamsAndDeduplicate()
    }

    @Test
    fun clipDataFallbackAcceptsFilesButIgnoresText() {
        val clip = ClipData.newRawUri("books", first).apply {
            addItem(ClipData.Item("not a file"))
            addItem(ClipData.Item(second))
        }
        assertEquals(listOf(first, second), sharedFileUris(Intent(Intent.ACTION_SEND_MULTIPLE).apply { clipData = clip }))
        assertEquals(listOf(first), sharedFileUris(Intent(Intent.ACTION_SEND).apply { clipData = clip }))
        assertTrue(sharedFileUris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "hello")).isEmpty())
    }

    @Test
    @Config(sdk = [28, 33])
    fun malformedStreamExtraStillAllowsClipDataFallback() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            val intent = Intent(action).putExtra(Intent.EXTRA_STREAM, "not a URI").apply {
                clipData = ClipData.newRawUri("books", first)
            }
            assertEquals(listOf(first), sharedFileUris(intent))
        }
    }

    @Test
    @Config(sdk = [28, 33])
    fun unreadableStreamParcelStillAllowsClipDataFallback() {
        val parcel = Parcel.obtain()
        try {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, BrokenStream()).apply {
                clipData = ClipData.newRawUri("books", first)
            }.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val delivered = Intent.CREATOR.createFromParcel(parcel).apply {
                setExtrasClassLoader(BrokenStream::class.java.classLoader)
            }
            assertEquals(listOf(first), sharedFileUris(delivered))
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun solidExplorerStyleFb2SharesAppendToTheBatch() {
        registerProvider()
        val context = RuntimeEnvironment.getApplication()
        val vm = ConverterViewModel()
        val fb2 = Uri.parse("content://shared-books/fb2")
        vm.receiveSharedFiles(
            Intent(Intent.ACTION_SEND).setType("application/octet-stream").apply {
                clipData = ClipData.newRawUri("books", fb2)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        vm.importPendingSharedFiles(context)
        awaitImport(vm)
        assertEquals(listOf("Книга.FB2"), vm.inputFileNames)
        assertTrue(vm.canConvert)

        val otherFb2 = Uri.parse("content://shared-books/other-fb2")
        vm.receiveSharedFiles(
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(fb2, otherFb2, first, second)),
        )
        vm.importPendingSharedFiles(context)
        awaitImport(vm)
        assertEquals(listOf(fb2, otherFb2, first, second), vm.inputUris)
        assertEquals(listOf("Книга.FB2", "second.fb2", "BOOK.EPUB", "second.pdf"), vm.inputFileNames)
        assertTrue(vm.isBatch)
        assertTrue(vm.canConvert)
    }

    @Test
    fun sharedContentUsesProviderDisplayNameAndCanBeReadByConversion() {
        registerProvider()
        val context = RuntimeEnvironment.getApplication()
        val vm = ConverterViewModel()
        vm.receiveSharedFiles(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first))
        vm.importPendingSharedFiles(context)
        awaitImport(vm)
        assertEquals(listOf(first), vm.inputUris)
        assertEquals(listOf("BOOK.EPUB"), vm.inputFileNames)
        assertTrue(vm.canConvert)
        // The conversion worker opens exactly this URI using ContentResolver, not a filesystem path.
        assertEquals("book data", context.contentResolver.openInputStream(vm.inputUris.single())!!.bufferedReader().use { it.readText() })
        assertTrue(vm.pendingSharedUris.isEmpty())
        vm.receiveSharedFiles(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first))
        vm.importPendingSharedFiles(context)
        assertEquals(listOf(first), vm.inputUris)
    }

    @Test
    fun mixedBatchKeepsReadableBooksAndReportsUnsupportedAndDeniedFiles() {
        registerProvider()
        val vm = ConverterViewModel()
        val denied = Uri.parse("content://shared-books/denied")
        val unsupported = Uri.parse("content://shared-books/image")
        vm.receiveSharedFiles(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, denied, unsupported)))
        vm.importPendingSharedFiles(RuntimeEnvironment.getApplication())
        awaitImport(vm)
        assertEquals(listOf(first, second), vm.inputUris)
        assertEquals(listOf("BOOK.EPUB", "second.pdf"), vm.inputFileNames)
        assertTrue(vm.canConvert)
        assertEquals(R.string.status_import_failed, vm.status.resourceId)
        assertEquals(listOf("1", "1"), vm.status.args)
        assertTrue(vm.status.isError)
    }

    @Test
    fun unsupportedAndNonContentUrisDoNotEnterQueue() {
        registerProvider()
        for ((uri, expected) in listOf(Uri.parse("content://shared-books/image") to R.string.status_unsupported_selection, Uri.parse("file:///private/book.epub") to R.string.status_import_failed)) {
            val vm = ConverterViewModel()
            vm.receiveSharedFiles(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri))
            vm.importPendingSharedFiles(RuntimeEnvironment.getApplication())
            awaitImport(vm)
            assertFalse(vm.hasSelection)
            assertEquals(expected, vm.status.resourceId)
            assertTrue(vm.status.isError)
        }
    }

    @Test
    fun emptyAndMalformedSharesAreErrorsButLauncherIsUnchanged() {
        val vm = ConverterViewModel()
        vm.receiveSharedFiles(Intent(Intent.ACTION_MAIN))
        assertEquals(StatusMessage(), vm.status)
        for (intent in listOf(Intent(Intent.ACTION_SEND), Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, "not a URI"))) {
            vm.receiveSharedFiles(intent)
            assertEquals(R.string.status_share_missing_files, vm.status.resourceId)
            assertTrue(vm.status.isError)
        }
    }

    @Test
    fun busyAppDefersSharesAndPreservesExistingSelectionAndResults() {
        registerProvider()
        val vm = ConverterViewModel()
        vm.inputUris = listOf(first)
        vm.inputFileNames = listOf("BOOK.EPUB")
        val status = StatusMessage(R.string.status_converting)
        vm.status = status
        for (state in 0..2) {
            vm.isConverting = state == 0
            vm.isSaving = state == 1
            vm.pendingSave = state == 2
            vm.receiveSharedFiles(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, second))
            vm.importPendingSharedFiles(RuntimeEnvironment.getApplication())
            assertEquals(listOf(first), vm.inputUris)
            assertEquals(status, vm.status)
            assertEquals(listOf(second), vm.pendingSharedUris)
        }
        vm.pendingSave = false
        vm.importPendingSharedFiles(RuntimeEnvironment.getApplication())
        awaitImport(vm)
        assertEquals(listOf(first, second), vm.inputUris)
    }

    @Test
    fun launcherAndNewIntentUseSameViewModelAndProcessRestorationRecoversShare() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).create()
        val activity = controller.get()
        val vm = ViewModelProvider(activity)[ConverterViewModel::class.java]
        assertTrue(vm.pendingSharedUris.isEmpty())
        val share = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first)
        controller.newIntent(share)
        assertEquals(listOf(first), vm.pendingSharedUris)
        assertEquals(share, activity.intent)
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
        controller.newIntent(multiple)
        assertSame(vm, ViewModelProvider(activity)[ConverterViewModel::class.java])
        assertEquals(listOf(first, second), vm.pendingSharedUris)
        assertEquals(multiple, activity.intent)
        controller.destroy()
        // Saved Activity state also exists after process death, when there is no retained ViewModel.
        val restored = Robolectric.buildActivity(MainActivity::class.java, multiple).create(android.os.Bundle())
        assertEquals(listOf(first, second), ViewModelProvider(restored.get())[ConverterViewModel::class.java].pendingSharedUris)
        restored.destroy()
    }

    @Test
    fun retainedViewModelDoesNotReplayTheInitialIntentAfterSelectionIsCleared() {
        registerProvider()
        val vm = ConverterViewModel()
        val share = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first)
        vm.receiveInitialIntent(share)
        vm.importPendingSharedFiles(RuntimeEnvironment.getApplication())
        awaitImport(vm)
        vm.clearSelection()
        vm.receiveInitialIntent(share)
        assertTrue(vm.pendingSharedUris.isEmpty())
        assertFalse(vm.hasSelection)
        // A new delivery of the same file must still work in an already open Activity.
        vm.receiveSharedFiles(share)
        assertEquals(listOf(first), vm.pendingSharedUris)
    }

    @Test
    fun coldShareIsDeliveredToTheActivityViewModel() {
        val share = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first)
        val controller = Robolectric.buildActivity(MainActivity::class.java, share).create()
        assertEquals(listOf(first), ViewModelProvider(controller.get())[ConverterViewModel::class.java].pendingSharedUris)
        controller.destroy()
    }

    @Test
    fun regularPickerRetainsItsExistingUnsupportedSelectionBehavior() {
        val context = RuntimeEnvironment.getApplication()
        val vm = ConverterViewModel()
        val original = Uri.parse("file:///books/original.epub")
        vm.addSelectedUris(context, listOf(original, Uri.parse("file:///books/unsupported.zip")))
        awaitImport(vm)
        assertEquals(listOf(original), vm.inputUris)
        assertEquals(StatusMessage(R.string.status_selected_skipped, listOf("1", "1")), vm.status)
        vm.addSelectedUris(context, listOf(Uri.parse("file:///books/unsupported.zip")))
        awaitImport(vm)
        assertEquals(listOf(original), vm.inputUris)
        assertEquals(StatusMessage(R.string.status_unsupported_selection), vm.status)
    }

    @Test
    fun manifestResolvesGenericFileSharesAndNormalLauncher() {
        val context = RuntimeEnvironment.getApplication()
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (mime in listOf("application/epub+zip", "application/fb2", "application/x-fictionbook+xml", "text/xml", "application/octet-stream", "application/pdf", "*/*")) {
                val intent = Intent(action).setType(mime).setPackage(context.packageName)
                assertTrue(context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).any { it.activityInfo.name == MainActivity::class.java.name })
            }
        }
        assertNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
    }

    private fun awaitImport(vm: ConverterViewModel) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (vm.isLoadingSelection && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Import did not finish", vm.isLoadingSelection)
    }

    private fun registerProvider() {
        val context = RuntimeEnvironment.getApplication()
        val provider = BookProvider()
        provider.attachInfo(
            context,
            ProviderInfo().apply {
                authority = "shared-books"
                exported = false
            },
        )
        ShadowContentResolver.registerProviderInternal("shared-books", provider)
    }

    class BrokenStream : Parcelable {
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) {}

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<BrokenStream> {
                override fun createFromParcel(source: Parcel): BrokenStream = throw BadParcelableException("Unreadable stream extra")
                override fun newArray(size: Int): Array<BrokenStream?> = arrayOfNulls(size)
            }
        }
    }

    class BookProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val name = when (uri.lastPathSegment) {
                "1" -> "BOOK.EPUB"
                "2" -> "second.pdf"
                "image" -> "image.jpg"
                "fb2" -> "Книга.FB2"
                "other-fb2" -> "second.fb2"
                else -> "denied.epub"
            }
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (uri.lastPathSegment == "denied") throw SecurityException("Read access denied")
            val file = File(requireNotNull(context).cacheDir, "share-test-book").apply { writeText("book data") }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
