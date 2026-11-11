package com.econverter.app

import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Rar7zSelectionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        documents.clear()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val provider = BooksProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "mixed-books" })
        ShadowContentResolver.registerProviderInternal("mixed-books", provider)
        val authority = "${context.packageName}.fileprovider"
        val files = FileProvider()
        files.attachInfo(context, requireNotNull(context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)))
        ShadowContentResolver.registerProviderInternal(authority, files)
    }

    @Test
    fun shareAndPickerUseOneQueueForBooksZipRar4Rar5AndSevenZ() {
        val ordinary = document("ordinary.fb2", "book".toByteArray())
        val zipBytes = ByteArrayOutputStream().use { buffer ->
            ZipOutputStream(buffer).use {
                it.putNextEntry(ZipEntry("zip.fb2"))
                it.write("zip".toByteArray())
                it.closeEntry()
            }
            buffer.toByteArray()
        }
        val zip = document("zip.fb2.zip", zipBytes)
        val rar4 = document("rar4.rar", fixture("rar4-books.rar"))
        val rar5 = document("rar5.rar", fixture("rar5-books.rar"))
        val seven = document("books.7z", sevenZ(File(context.cacheDir, "test.7z"), "Папка/один.fb2" to "one", "два.fb2" to "two", "nested.rar" to "ignored"))
        val corrupt = document("corrupt.rar", byteArrayOf(1, 2))
        val tar = document("unsupported.tar", "ustar".toByteArray())
        val sources = listOf(ordinary, zip, rar4, rar5, seven, corrupt, tar)
        for (shared in listOf(true, false)) {
            val vm = ConverterViewModel().apply { archiveReaderFactory = ::testArchiveReader }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(sources))
            if (shared) {
                vm.receiveInitialIntent(intent)
                vm.importPendingSharedFiles(context)
            } else {
                vm.addSelectedUris(context, sources)
            }
            awaitImport(vm)
            assertEquals(vm.status.text(context), 7, vm.inputUris.size)
            assertTrue(vm.isBatch)
            assertTrue(vm.canConvert)
            assertTrue(vm.status.isError)
            assertEquals("2", vm.status.args[1])
            val originalSelection = vm.inputUris
            vm.receiveInitialIntent(intent)
            vm.receiveSharedFiles(intent)
            vm.importPendingSharedFiles(context)
            awaitImport(vm)
            assertEquals(originalSelection, vm.inputUris)
            for (uri in vm.inputUris) context.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read() >= 0) }
            vm.finishZipConversion(vm.inputUris)
            assertEquals(listOf(ordinary), vm.inputUris)
            assertTrue(File(context.filesDir, ZIP_IMPORTS_DIRECTORY).walkTopDown().none { it.isFile })
            val history = HistoryEntry("epub", StatusMessage(), 1, listOf(rar4, rar5, seven), listOf("rar4.rar", "rar5.rar", "books.7z"))
            vm.selectHistory(history, context)
            awaitImport(vm)
            assertEquals(5, vm.inputUris.size)
            vm.clearSelection()
            assertArrayEquals(fixture("rar5-books.rar"), documents.getValue(rar5).second.readBytes())
        }
    }

    private fun awaitImport(vm: ConverterViewModel) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (vm.isLoadingSelection && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(vm.isLoadingSelection)
    }
    private fun document(name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://mixed-books/${documents.size}")
        documents[uri] = name to File(context.cacheDir, "mixed-${documents.size}").apply { writeBytes(bytes) }
        return uri
    }
    class BooksProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(documents.getValue(uri).first)) }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(documents.getValue(uri).second, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
    companion object {
        private val documents = mutableMapOf<Uri, Pair<String, File>>()
    }
}
