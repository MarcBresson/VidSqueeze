package app.squeeze.finalize

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.squeeze.data.BatchEntity
import app.squeeze.data.BatchItemEntity
import app.squeeze.data.BatchSource
import app.squeeze.data.BatchStatus
import app.squeeze.data.FinalizeState
import app.squeeze.data.ItemStatus
import app.squeeze.data.ResultsRepository
import app.squeeze.data.SqueezeDatabase
import app.squeeze.engine.BuiltInPresets
import app.squeeze.engine.ExportMode
import app.squeeze.engine.FfmpegKitEngine
import app.squeeze.engine.RunResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs [Finalizer] against the real MediaStore (the app owns every file it inserts, so no consent is needed). */
@RunWith(AndroidJUnit4::class)
class FinalizerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = FfmpegKitEngine(context)
    private val resolver = context.contentResolver
    private val store = MediaStoreWriter(context)
    private lateinit var db: SqueezeDatabase
    private lateinit var results: ResultsRepository
    private lateinit var finalizer: Finalizer
    private lateinit var dir: File
    private val inserted = mutableListOf<Uri>()
    private var nextId = 1L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SqueezeDatabase::class.java).allowMainThreadQueries().build()
        results = ResultsRepository(db.batchDao(), db.batchItemDao())
        finalizer = Finalizer(context, results, store, engine)
        dir = File(context.filesDir, "staging/finalizer-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        inserted.forEach { runCatching { resolver.delete(it, null, null) } }
        resolver.delete(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("Movies/SqueezeTest%"),
        )
        db.close()
        dir.deleteRecursively()
    }

    private fun clip(name: String, seconds: Int, size: String, crf: Int): File {
        val out = File(dir, name)
        val args = listOf(
            "-y", "-f", "lavfi", "-i", "testsrc2=size=$size:rate=30:duration=$seconds",
            "-c:v", "libx264", "-preset", "ultrafast", "-crf", "$crf", "-pix_fmt", "yuv420p",
            "-metadata", "creation_time=2020-09-13T12:26:40Z", out.absolutePath,
        )
        assertEquals(RunResult.Success, runBlocking { engine.run(args, seconds * 1000L) })
        return out
    }

    private fun insertOriginal(file: File, name: String, mime: String, dateTaken: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SqueezeTest/")
            put(MediaStore.Video.Media.DATE_TAKEN, dateTaken)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        inserted += uri
        // MediaStore reads the capture date from the file's metadata a moment after the write.
        awaitColumn(uri, MediaStore.Video.Media.DATE_TAKEN, dateTaken.toString())
        return uri
    }

    private fun column(uri: Uri, column: String): String? =
        resolver.query(uri, arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** MediaStore scans a file shortly after it was written; waits until [column] shows [expected]. */
    private fun awaitColumn(uri: Uri, column: String, expected: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (column(uri, column) != expected && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertEquals(expected, column(uri, column))
    }

    private fun exists(uri: Uri) = resolver.query(uri, arrayOf(MediaStore.Video.Media._ID), null, null, null)?.use { it.count > 0 } ?: false

    /** A batch with one compressed result waiting; returns its item. */
    private fun awaiting(
        staged: File, original: Uri?, displayName: String, originalSize: Long, durationMs: Long = 2_000, imported: Boolean = false,
    ): BatchItemEntity = runBlocking {
        val batchId = db.batchDao().insert(
            BatchEntity(
                source = BatchSource.PICKER, presetId = null, presetName = "Test", presetSnapshotJson = "{}",
                exportMode = ExportMode.ASK_WHEN_DONE, createdAt = 1, finishedAt = 2, status = BatchStatus.AWAITING_ACTION,
            ),
        )
        val item = BatchItemEntity(
            batchId = batchId, position = nextId++, sourceUri = original?.toString() ?: "file:///x",
            mediaStoreId = original?.lastPathSegment?.toLong(), isImported = imported, displayName = displayName,
            originalSize = originalSize, outputSize = staged.length(), durationMs = durationMs, status = ItemStatus.DONE,
            stagedPath = staged.absolutePath,
        )
        val id = db.batchItemDao().insertAll(listOf(item)).single()
        item.copy(id = id)
    }

    private val dateTaken = 1_600_000_000_000L // 2020-09-13T12:26:40Z

    @Test
    fun saveCopyLandsNextToTheOriginalWithItsDate() = runBlocking {
        val big = clip("big.mp4", 2, "1280x720", 18)
        val small = File(dir, "big - compressed.mp4").also { clip("small.mp4", 2, "640x360", 35).copyTo(it) }
        val original = insertOriginal(big, "big.mp4", "video/mp4", dateTaken)
        val item = awaiting(small, original, "big.mp4", big.length())
        val smallSize = small.length()

        val report = finalizer.saveCopies(item.batchId)

        assertEquals(1, report.savedAsCopy)
        val saved = db.batchItemDao().get(item.id)!!
        assertEquals(FinalizeState.SAVED, saved.finalizeState)
        assertNull(saved.stagedPath)
        assertFalse(small.exists())
        val copy = Uri.parse(saved.finalUri)
        inserted += copy
        assertNotEquals(original, copy)
        assertEquals("big - compressed.mp4", column(copy, MediaStore.Video.Media.DISPLAY_NAME))
        assertEquals("Movies/SqueezeTest/", column(copy, MediaStore.Video.Media.RELATIVE_PATH))
        assertEquals("0", column(copy, MediaStore.Video.Media.IS_PENDING))
        awaitColumn(copy, MediaStore.Video.Media.DATE_TAKEN, dateTaken.toString())
        awaitColumn(copy, MediaStore.Video.Media.SIZE, smallSize.toString())
        assertTrue(exists(original))
        assertEquals(BatchStatus.DONE, db.batchDao().get(item.batchId)!!.status)
    }

    /** Saves a compressed copy of a freshly inserted original and returns both URIs plus the item. */
    private fun savedCopy(): Triple<Uri, Uri, BatchItemEntity> = runBlocking {
        val big = clip("big.mp4", 2, "1280x720", 18)
        val small = File(dir, "big - compressed.mp4").also { clip("small.mp4", 2, "640x360", 35).copyTo(it) }
        val original = insertOriginal(big, "big.mp4", "video/mp4", dateTaken)
        val item = awaiting(small, original, "big.mp4", big.length())
        finalizer.saveCopies(item.batchId)
        val saved = db.batchItemDao().get(item.id)!!
        val copy = Uri.parse(saved.finalUri)
        inserted += copy
        Triple(original, copy, saved)
    }

    @Test
    fun validatingTheReplacementDeletesTheOriginalAndGivesTheCopyItsName() = runBlocking {
        val (original, copy, item) = savedCopy()

        val result = finalizer.validateReplacement(item.id, keepOriginalName = true)

        assertEquals(ReplaceAfterSaveResult.Done("big.mp4"), result)
        assertFalse(exists(original))
        assertTrue(exists(copy))
        assertEquals("big.mp4", column(copy, MediaStore.Video.Media.DISPLAY_NAME))
        assertEquals("Movies/SqueezeTest/", column(copy, MediaStore.Video.Media.RELATIVE_PATH))
        val after = db.batchItemDao().get(item.id)!!
        assertEquals(FinalizeState.REPLACED, after.finalizeState)
        assertEquals(copy.toString(), after.finalUri)
    }

    @Test
    fun validatingTheReplacementCanKeepTheCopysOwnName() = runBlocking {
        val (original, copy, item) = savedCopy()

        val result = finalizer.validateReplacement(item.id, keepOriginalName = false)

        assertEquals(ReplaceAfterSaveResult.Done("big - compressed.mp4"), result)
        assertFalse(exists(original))
        assertEquals("big - compressed.mp4", column(copy, MediaStore.Video.Media.DISPLAY_NAME))
    }

    @Test
    fun validatingAgainAfterTheOriginalIsGoneStillRenamesTheCopy() = runBlocking {
        val (original, copy, item) = savedCopy()
        resolver.delete(original, null, null)

        assertEquals(ReplaceAfterSaveResult.Done("big.mp4"), finalizer.validateReplacement(item.id, keepOriginalName = true))
        assertEquals("big.mp4", column(copy, MediaStore.Video.Media.DISPLAY_NAME))
    }

    @Test
    fun anItemThatWasNotSavedAsACopyHasNothingToValidate() = runBlocking {
        val big = clip("big.mp4", 2, "1280x720", 18)
        val staged = File(dir, "big - compressed.mp4").also { clip("small.mp4", 2, "640x360", 35).copyTo(it) }
        val original = insertOriginal(big, "big.mp4", "video/mp4", dateTaken)
        val item = awaiting(staged, original, "big.mp4", big.length())

        assertEquals(ReplaceAfterSaveResult.Unavailable, finalizer.validateReplacement(item.id, keepOriginalName = true))
        assertTrue(exists(original))
    }

    @Test
    fun aMissingCopyNeverDeletesTheOriginal() = runBlocking {
        val (original, copy, item) = savedCopy()
        resolver.delete(copy, null, null)

        assertTrue(finalizer.validateReplacement(item.id, keepOriginalName = true) is ReplaceAfterSaveResult.Failed)
        assertTrue(exists(original))
        assertEquals(FinalizeState.SAVED, db.batchItemDao().get(item.id)!!.finalizeState)
    }

    @Test
    fun savingTwiceNeverOverwritesAnExistingCopy() = runBlocking {
        val big = clip("big.mp4", 2, "1280x720", 18)
        val original = insertOriginal(big, "big.mp4", "video/mp4", dateTaken)
        val uris = (1..2).map {
            val staged = File(dir, "$it/big - compressed.mp4").also { f -> f.parentFile!!.mkdirs(); clip("s$it.mp4", 2, "640x360", 35).copyTo(f) }
            val item = awaiting(staged, original, "big.mp4", big.length())
            finalizer.saveCopies(item.batchId)
            Uri.parse(db.batchItemDao().get(item.id)!!.finalUri).also { inserted += it }
        }
        assertEquals("big - compressed.mp4", column(uris[0], MediaStore.Video.Media.DISPLAY_NAME))
        assertEquals("big - compressed (1).mp4", column(uris[1], MediaStore.Video.Media.DISPLAY_NAME))
    }

    @Test
    fun replaceOverwritesTheOriginalInPlace() = runBlocking {
        val big = clip("big.mp4", 2, "1280x720", 18)
        val small = File(dir, "stage/1/small.mp4").also { f -> f.parentFile!!.mkdirs(); clip("s.mp4", 2, "640x360", 35).copyTo(f) }
        val original = insertOriginal(big, "big.mp4", "video/mp4", dateTaken)
        val item = awaiting(small, original, "big.mp4", big.length())
        val smallSize = small.length()
        assertTrue(smallSize < big.length())

        assertTrue(finalizer.needsWriteConsent(item.batchId).isEmpty())
        val report = finalizer.replace(item.batchId)

        assertEquals("failures: ${report.failures}", 1, report.replaced)
        val done = db.batchItemDao().get(item.id)!!
        assertEquals(FinalizeState.REPLACED, done.finalizeState)
        // MediaStore reports the volume as external_primary; the id is what identifies the row.
        assertEquals(ContentUris.parseId(original), ContentUris.parseId(Uri.parse(done.finalUri)))
        awaitColumn(original, MediaStore.Video.Media.SIZE, smallSize.toString())
        awaitColumn(original, MediaStore.Video.Media.DATE_TAKEN, dateTaken.toString())
        assertEquals("big.mp4", column(original, MediaStore.Video.Media.DISPLAY_NAME))
        assertFalse(small.exists())
        // What the gallery now plays is the compressed video.
        val info = engine.probe(engine.inputFor(original), "mp4")!!
        assertEquals(640, info.width)
    }

    @Test
    fun replaceWithAnotherContainerAddsTheNewFileAndRemovesTheOld() = runBlocking {
        val mp4 = clip("src.mp4", 2, "1280x720", 18)
        val mkv = File(dir, "src.mkv")
        assertEquals(RunResult.Success, engine.run(listOf("-y", "-i", mp4.absolutePath, "-c", "copy", mkv.absolutePath), 2000))
        val small = File(dir, "stage/2/clip.mp4").also { f -> f.parentFile!!.mkdirs(); clip("s.mp4", 2, "640x360", 35).copyTo(f) }
        val original = insertOriginal(mkv, "clip.mkv", "video/x-matroska", dateTaken)
        val item = awaiting(small, original, "clip.mkv", mkv.length())

        val report = finalizer.replace(item.batchId)

        assertEquals("failures: ${report.failures}", 1, report.replaced)
        assertTrue("the app owns the file, so it can delete it itself", report.originalsToDelete.isEmpty())
        assertFalse(exists(original))
        val copy = Uri.parse(db.batchItemDao().get(item.id)!!.finalUri)
        inserted += copy
        assertEquals("clip.mp4", column(copy, MediaStore.Video.Media.DISPLAY_NAME))
        awaitColumn(copy, MediaStore.Video.Media.DATE_TAKEN, dateTaken.toString())
        assertEquals(FinalizeState.REPLACED, db.batchItemDao().get(item.id)!!.finalizeState)
    }

    @Test
    fun importedSourcesAreSavedAsCopiesInstead() = runBlocking {
        val small = File(dir, "stage/3/shared - compressed.mp4").also { f -> f.parentFile!!.mkdirs(); clip("s.mp4", 2, "640x360", 35).copyTo(f) }
        val item = awaiting(small, original = null, displayName = "shared.mp4", originalSize = 5_000_000, imported = true)

        val report = finalizer.replace(item.batchId)

        assertEquals(0, report.replaced)
        assertEquals(1, report.savedAsCopy)
        val saved = db.batchItemDao().get(item.id)!!
        assertEquals(FinalizeState.SAVED, saved.finalizeState)
        val copy = Uri.parse(saved.finalUri)
        inserted += copy
        assertEquals("Movies/Squeeze/", column(copy, MediaStore.Video.Media.RELATIVE_PATH))
    }

    @Test
    fun aBrokenResultNeverTouchesTheOriginal() = runBlocking {
        val big = clip("big.mp4", 2, "1280x720", 18)
        val original = insertOriginal(big, "big.mp4", "video/mp4", dateTaken)
        val broken = File(dir, "stage/4/broken.mp4").also { f -> f.parentFile!!.mkdirs(); f.writeText("not a video") }
        val item = awaiting(broken, original, "big.mp4", big.length())

        val report = finalizer.replace(item.batchId)

        assertEquals(0, report.replaced)
        assertEquals(1, report.failures.size)
        awaitColumn(original, MediaStore.Video.Media.SIZE, big.length().toString())
        assertTrue("the result stays so it can be saved or retried", broken.exists())
        assertEquals(FinalizeState.PENDING, db.batchItemDao().get(item.id)!!.finalizeState)
        assertEquals(BatchStatus.AWAITING_ACTION, db.batchDao().get(item.batchId)!!.status)
    }

    @Test
    fun discardDeletesStagedFilesAndLeavesOriginalsAlone() = runBlocking {
        val big = clip("big.mp4", 2, "1280x720", 18)
        val original = insertOriginal(big, "big.mp4", "video/mp4", dateTaken)
        val small = File(dir, "stage/5/small.mp4").also { f -> f.parentFile!!.mkdirs(); clip("s.mp4", 2, "640x360", 35).copyTo(f) }
        val item = awaiting(small, original, "big.mp4", big.length())

        finalizer.discard(item.batchId)

        assertFalse(small.exists())
        assertEquals(FinalizeState.DISCARDED, db.batchItemDao().get(item.id)!!.finalizeState)
        assertEquals(BatchStatus.DONE, db.batchDao().get(item.batchId)!!.status)
        assertTrue(exists(original))
    }

    @Test
    fun oldPendingBatchesAreDiscarded() = runBlocking {
        val small = File(dir, "stage/6/small.mp4").also { f -> f.parentFile!!.mkdirs(); f.writeText("x") }
        val item = awaiting(small, null, "a.mp4", 100)
        // The batch finished at t=2ms; one day later with a 1 h limit it is expired.
        assertEquals(listOf(item.batchId), finalizer.discardOlderThan(3_600_000, now = 86_400_000))
        assertFalse(small.exists())
        assertTrue(finalizer.discardOlderThan(3_600_000, now = 86_400_000).isEmpty())
    }

    @Test
    fun clearingDiscardsEveryPendingResultWhateverItsAge() = runBlocking {
        val small = File(dir, "stage/7/small.mp4").also { f -> f.parentFile!!.mkdirs(); f.writeText("x") }
        val item = awaiting(small, null, "a.mp4", 100)
        // The batch finished at t=2ms, i.e. just now as far as the clock in this test is concerned.
        assertEquals(listOf(item.batchId), finalizer.discardAllPending(now = 5))
        assertFalse(small.exists())
        assertEquals(FinalizeState.DISCARDED, db.batchItemDao().get(item.id)!!.finalizeState)
    }

    @Test
    fun emptyStagingFoldersAreSweptButFilesAndFreshFoldersStay() = runBlocking {
        val root = File(dir, "sweep").apply { mkdirs() }
        val old = File(root, "1/2").apply { mkdirs() }
        val holdsFile = File(root, "3/4").apply { mkdirs() }.also { File(it, "r.mp4").writeText("x") }
        val fresh = File(root, "5/6").apply { mkdirs() }
        val past = System.currentTimeMillis() - 10 * 60_000
        listOf(old, old.parentFile!!).forEach { it.setLastModified(past) }

        finalizer.pruneEmptyStagingDirs(root)

        assertFalse("an old empty item folder and its empty batch folder go", old.exists() || old.parentFile!!.exists())
        assertTrue(File(holdsFile, "r.mp4").exists())
        assertTrue("a folder the queue may be about to use is left alone", fresh.exists())
        assertTrue(root.exists())
    }

    @Test
    fun reshareUsesFileProviderUrisThatOtherAppsCanRead() = runBlocking {
        val a = File(dir, "a - compressed.mp4").also { clip("a.mp4", 1, "320x180", 35).copyTo(it) }
        val item = awaiting(a, null, "a.mp4", 100)

        val chooser = requireNotNull(finalizer.shareIntent(item.batchId))
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!

        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("video/*", send.type)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertEquals("a - compressed.mp4", uri.lastPathSegment)
        assertNotNull(send.clipData)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(a.length(), resolver.openAssetFileDescriptor(uri, "r")!!.use { it.length })
        assertTrue(a.exists())
    }

    private fun assertNotEquals(a: Any, b: Any) = assertTrue("$a should differ from $b", a != b)
}
