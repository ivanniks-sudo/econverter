package com.econverter.app

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
class ZipSelectionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        documents.clear()
        File(context.filesDir, ZIP_IMPORTS_DIRECTORY).deleteRecursively()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val provider = BooksProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "zip-books" })
        ShadowContentResolver.registerProviderInternal("zip-books", provider)
        // Robolectric changes filesDir between tests; refresh cached FileProvider roots.
        val authority = "${context.packageName}.fileprovider"
        val filesProvider = FileProvider()
        filesProvider.attachInfo(context, requireNotNull(context.packageManager.resolveContentProvider(authority, android.content.pm.PackageManager.GET_META_DATA)))
        ShadowContentResolver.registerProviderInternal(authority, filesProvider)
    }

    @Test
    fun shareAndPickerImportMultipleArchivesAndRegularBooksWithoutChangingSources() {
        val first = document("Книги.fb2.zip", zip("folder/Книга.FB2" to "first", "other/Книга.FB2" to "second", "cover.jpg" to "image", "nested.zip" to "ignored"))
        val second = document("second.zip", zip("second.epub" to "third"))
        val regular = document("normal.fb2", "normal".toByteArray())
        val broken = document("broken.zip", byteArrayOf(1, 2, 3))
        val original = documents.getValue(first).second.readBytes()
        for (shared in listOf(true, false)) {
            val vm = ConverterViewModel()
            val sources = listOf(first, second, regular, broken, first)
            if (shared) {
                vm.receiveSharedFiles(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(sources)))
                vm.importPendingSharedFiles(context)
            } else {
                vm.addSelectedUris(context, sources)
            }
            awaitIdle(vm)
            assertEquals(listOf("Книга.FB2", "Книга.FB2", "second.epub", "normal.fb2"), vm.inputFileNames)
            assertTrue(vm.canConvert)
            assertTrue(vm.isBatch)
            assertEquals(listOf("first", "second", "third", "normal"), vm.inputUris.map { uri -> context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } })
            assertEquals(R.string.status_zip_import_summary, vm.status.resourceId)
            assertEquals("2", vm.status.args[1])
            assertTrue(vm.status.isError)
            vm.clearSelection()
            assertTrue(importedFiles().isEmpty())
        }
        assertArrayEquals(original, documents.getValue(first).second.readBytes())
    }

    @Test
    fun initialAndWarmSingleSharesAreDeduplicatedAndDeferredWhileBusy() {
        val source = document("one.fb2.zip", zip("one.fb2" to "one"))
        val share = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, source)
        val vm = ConverterViewModel()
        vm.receiveInitialIntent(share)
        vm.importPendingSharedFiles(context)
        awaitIdle(vm)
        val originalUris = vm.inputUris
        vm.receiveInitialIntent(share)
        vm.receiveSharedFiles(share)
        vm.importPendingSharedFiles(context)
        awaitIdle(vm)
        assertEquals(originalUris, vm.inputUris)

        val next = document("two.zip", zip("two.fb2" to "two"))
        vm.pendingSave = true
        vm.receiveSharedFiles(Intent(Intent.ACTION_SEND).apply { clipData = ClipData.newRawUri("books", next) })
        vm.importPendingSharedFiles(context)
        assertEquals(originalUris, vm.inputUris)
        assertEquals(listOf(next), vm.pendingSharedUris)
        vm.pendingSave = false
        vm.importPendingSharedFiles(context)
        awaitIdle(vm)
        assertEquals(vm.status.text(context), listOf("one.fb2", "two.fb2"), vm.inputFileNames)
        vm.removeSelectedUri(vm.inputUris.first())
        assertEquals(1, importedFiles().size)
        vm.clearSelection()
        assertTrue(importedFiles().isEmpty())
    }

    @Test
    fun rejectedArchiveDoesNotLeavePartialBooksOrBlockOtherInputs() {
        val source = document("unsafe.zip", zip("good.fb2" to "first", "../escape.fb2" to "evil"))
        val empty = document("empty.zip", zip())
        val normal = document("normal.fb2", "normal".toByteArray())
        val vm = ConverterViewModel()
        vm.addSelectedUris(context, listOf(source, empty, normal))
        awaitIdle(vm)
        assertEquals(listOf(normal), vm.inputUris)
        assertTrue(vm.status.isError)
        assertTrue(vm.status.text(context).contains(context.getString(R.string.zip_unsafe)))
        assertTrue(vm.status.text(context).contains(context.getString(R.string.zip_empty)))
        assertTrue(importedFiles().isEmpty())
    }

    @Test
    fun conversionSuccessErrorAndCancelCleanZipInputsAndHistoryReimportsOriginalArchive() {
        val source = document("books.zip", zip("one.fb2" to "one", "two.fb2" to "two"))
        for (outcome in listOf("success", "error", "cancel", "missing", "empty-success")) {
            val vm = ConverterViewModel()
            vm.addSelectedUris(context, listOf(source))
            awaitIdle(vm)
            var requestedNames = emptyList<String>()
            lateinit var reply: Messenger
            lateinit var request: Bundle
            fun finish() {
                val files = if (outcome == "success") {
                    listOf(
                        File(request.getString("directory")!!, "one.epub").apply {
                            parentFile!!.mkdirs()
                            writeText("converted")
                        },
                    )
                } else if (outcome == "missing") {
                    listOf(File(request.getString("directory")!!, "missing.epub"))
                } else {
                    emptyList()
                }
                val status = when (outcome) {
                    "success", "missing", "empty-success" -> StatusMessage(R.string.status_done, isSuccess = true)
                    "cancel" -> StatusMessage(R.string.status_cancelled)
                    else -> StatusMessage(R.string.status_error, listOf("Test failure"), isError = true)
                }
                reply.send(
                    Message.obtain(null, ConversionService.DONE).apply {
                        data = status.toBundle().apply { putStringArrayList("files", ArrayList(files.map { it.absolutePath })) }
                    },
                )
            }
            val service = Messenger(
                Handler(Looper.getMainLooper()) { message ->
                    when (message.what) {
                        ConversionService.RUN -> {
                            request = message.data
                            reply = message.replyTo
                            requestedNames = request.getStringArrayList("names").orEmpty()
                            if (outcome == "cancel") vm.cancelConversion() else finish()
                        }

                        ConversionService.CANCEL -> finish()
                    }
                    true
                },
            )
            val workerContext = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                    Handler(Looper.getMainLooper()).post { connection.onServiceConnected(ComponentName(this, ConversionService::class.java), service.binder) }
                    return true
                }
                override fun unbindService(connection: ServiceConnection) {}
            }
            vm.convert(workerContext)
            awaitIdle(vm)
            assertEquals(listOf("one.fb2", "two.fb2"), requestedNames)
            assertFalse(vm.hasSelection)
            assertTrue(importedFiles().isEmpty())
            assertEquals(listOf(source), vm.history.single().uris)
            if (outcome == "missing" || outcome == "empty-success") {
                assertEquals(R.string.status_missing_outputs, vm.status.resourceId)
                assertFalse(vm.pendingSave)
                assertNull(vm.createGmailIntent(context))
                assertNull(vm.createShareIntent(context))
            }
            if (outcome == "success") {
                assertTrue(vm.pendingSave)
                assertNotNull(vm.createShareIntent(context))
                assertEquals("com.google.android.gm", vm.createGmailIntent(context)!!.getPackage())
                assertEquals("converted", vm.outputFiles.single().readText())
                vm.discardPendingSave()
            }
            val entry = vm.history.single()
            vm.selectHistory(entry, context)
            awaitIdle(vm)
            assertEquals(vm.status.text(context), listOf("one.fb2", "two.fb2"), vm.inputFileNames)
            vm.clearSelection()
        }
    }

    @Test
    fun gmailZipAttachmentsSurviveDeselectionAndExpireUsingExistingRetentionWindow() {
        val source = document("book.zip", zip("book.fb2" to "book"))
        val vm = ConverterViewModel()
        vm.addSelectedUris(context, listOf(source))
        awaitIdle(vm)
        val intent = vm.createGmailIntent(context)!!
        val attachment = intent.clipData!!.getItemAt(0).uri
        vm.clearSelection()
        assertEquals("book", context.contentResolver.openInputStream(attachment)!!.bufferedReader().use { it.readText() })
        val root = File(context.filesDir, ZIP_IMPORTS_DIRECTORY)
        root.listFiles()!!.forEach { it.setLastModified(1) }
        vm.purgeStaleTempFiles(context, 2)
        awaitCondition { root.listFiles().orEmpty().isEmpty() }
    }

    @Test
    fun viewModelClearingRemovesUnsharedInputsAndPurgePreservesActiveSelection() {
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[ConverterViewModel::class.java]
        val source = document("book.zip", zip("book.fb2" to "book"))
        vm.addSelectedUris(context, listOf(source))
        awaitIdle(vm)
        val root = File(context.filesDir, ZIP_IMPORTS_DIRECTORY)
        root.listFiles()!!.forEach { it.setLastModified(1) }
        val abandoned = File(root, "abandoned").apply { mkdirs() }
        File(abandoned, "partial").writeText("unfinished")
        vm.purgeStaleTempFiles(context, Long.MAX_VALUE)
        awaitCondition { !abandoned.exists() }
        assertEquals(1, importedFiles().size)
        store.clear()
        awaitCondition { importedFiles().isEmpty() }
    }

    private fun importedFiles(): List<File> = File(context.filesDir, ZIP_IMPORTS_DIRECTORY).walkTopDown().filter { it.isFile && it.name != ".shared" }.toList()

    private fun awaitIdle(vm: ConverterViewModel) = awaitCondition { !vm.isLoadingSelection && !vm.isConverting }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Operation did not finish", condition())
    }

    private fun document(name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://zip-books/${documents.size}")
        val file = File(context.cacheDir, "zip-source-${documents.size}").apply { writeBytes(bytes) }
        documents[uri] = name to file
        return uri
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { buffer ->
        ZipOutputStream(buffer).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        buffer.toByteArray()
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
