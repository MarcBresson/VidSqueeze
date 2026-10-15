package app.squeeze.auto

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import app.squeeze.automation.AutomationEvents
import app.squeeze.data.AutoSettingsRepository
import app.squeeze.data.BatchSource
import app.squeeze.data.ItemResult
import app.squeeze.data.PresetRepository
import app.squeeze.data.QueueRepository
import app.squeeze.data.QueuedSource
import app.squeeze.data.ResultsRepository
import app.squeeze.data.SourceImporter
import app.squeeze.data.SqueezeDatabase
import app.squeeze.engine.BuiltInPresets
import app.squeeze.engine.ExportMode
import app.squeeze.engine.FfmpegKitEngine
import app.squeeze.engine.RunResult
import app.squeeze.finalize.BatchCompletion
import app.squeeze.finalize.Finalizer
import app.squeeze.finalize.MediaStoreWriter
import app.squeeze.work.CurrentJob
import app.squeeze.work.NotificationHelper
import app.squeeze.work.QueueManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Plans auto runs against real MediaStore rows (the clips carry a 2020 capture date). */
@RunWith(AndroidJUnit4::class)
class AutoRunnerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = FfmpegKitEngine(context)
    private val resolver = context.contentResolver
    private lateinit var db: SqueezeDatabase
    private lateinit var queue: QueueRepository
    private lateinit var runner: AutoRunner
    private lateinit var dir: File

    /** Ignores size and bitrate so the tiny test clips qualify. */
    private val config = AutoConfig(
        enabled = true, minSizeMb = 0, minBitrateKbps = 0,
        tiers = listOf(AgeTier(30, BuiltInPresets.SHARE_ID), AgeTier(365, BuiltInPresets.MAX_SAVINGS_ID)),
        exportMode = ExportMode.SAVE_COPIES,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SqueezeDatabase::class.java).allowMainThreadQueries().build()
        queue = QueueRepository(db.batchDao(), db.batchItemDao())
        val settings = AutoSettingsRepository(context)
        val store = MediaStoreWriter(context)
        val results = ResultsRepository(db.batchDao(), db.batchItemDao())
        val completion = BatchCompletion(results, Finalizer(context, results, store, engine), store, NotificationHelper(context), AutomationEvents(context, settings))
        val manager = QueueManager(
            queue, SourceImporter(context), WorkManager.getInstance(context), CurrentJob(engine), completion,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        runner = AutoRunner(context, settings, GalleryScanner(context), queue, manager, PresetRepository(db.presetDao()), DeviceStateReader(context), store)
        dir = File(context.cacheDir, "auto-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("Movies/SqueezeTest%"))
        db.close()
        dir.deleteRecursively()
    }

    private fun insert(name: String, folder: String, creationTime: String): Long {
        val file = File(dir, name)
        val args = listOf(
            "-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30:duration=1", "-c:v", "libx264", "-preset", "ultrafast",
            "-pix_fmt", "yuv420p", "-metadata", "creation_time=$creationTime", file.absolutePath,
        )
        assertEquals(RunResult.Success, runBlocking { engine.run(args, 1000L) })
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, folder)
        }
        val uri: Uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        val id = uri.lastPathSegment!!.toLong()
        // The scan after the write fills in the capture date from the file's metadata.
        val deadline = System.currentTimeMillis() + 10_000
        while (dateTaken(uri) == null && System.currentTimeMillis() < deadline) Thread.sleep(200)
        return id
    }

    private fun dateTaken(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.Video.Media.DATE_TAKEN), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    @Test
    fun oldVideosGetTheirTierPresetAndNewOnesAreLeftAlone() = runBlocking {
        val old = insert("ancient.mp4", "Movies/SqueezeTest/", "2020-09-13T12:26:40Z")
        val recent = insert("fresh.mp4", "Movies/SqueezeTest/", java.time.Instant.now().minusSeconds(3600).toString())

        val plan = runner.plan(config).associateBy { it.video.id }

        assertEquals(BuiltInPresets.MAX_SAVINGS_ID, plan.getValue(old).preset.id)
        assertEquals(ExportMode.SAVE_COPIES, plan.getValue(old).preset.export.mode)
        assertNull("a video newer than every tier must not be listed", plan[recent])
    }

    @Test
    fun videosAlreadyHandledAreNotPlannedAgain() = runBlocking {
        val id = insert("done-before.mp4", "Movies/SqueezeTest/", "2020-09-13T12:26:40Z")
        assertTrue(runner.plan(config).any { it.video.id == id })

        val batch = queue.enqueue(BatchSource.PICKER, BuiltInPresets.share, listOf(QueuedSource("content://x/$id", id, false, "done-before.mp4", 1000)))
        val item = db.batchItemDao().forBatch(batch).single()
        assertTrue("a queued video is skipped too", runner.plan(config).none { it.video.id == id })

        queue.markRunning(item)
        queue.complete(item.id, ItemResult.NoGain(1200, "cmd", 1000))
        assertTrue("a video that cannot get smaller is not retried every night", runner.plan(config).none { it.video.id == id })
    }

    @Test
    fun aFolderCommandStaysInsideItsFolderAndUsesItsOwnAgeRule() = runBlocking {
        val inside = insert("inside.mp4", "Movies/SqueezeTest/Sub/", java.time.Instant.now().minusSeconds(86_400 * 2).toString())
        val sibling = insert("sibling.mp4", "Movies/SqueezeTest2/", "2020-09-13T12:26:40Z")

        val plan = runner.plan(
            config,
            RunOptions(Trigger.EXTERNAL, folder = "Movies/SqueezeTest", olderThanDays = 1, presetOverride = "Share (WhatsApp-friendly)", exportModeOverride = ExportMode.ASK_WHEN_DONE),
        ).associateBy { it.video.id }

        assertEquals(setOf(inside), plan.keys.intersect(setOf(inside, sibling)))
        assertEquals(BuiltInPresets.SHARE_ID, plan.getValue(inside).preset.id)
        assertEquals(ExportMode.ASK_WHEN_DONE, plan.getValue(inside).preset.export.mode)
    }

    @Test
    fun aVideoSavedInOurOwnFolderIsNeverPlanned() = runBlocking {
        val own = insert("ours.mp4", "Movies/Squeeze/", "2020-09-13T12:26:40Z")
        try {
            assertTrue(runner.plan(config).none { it.video.id == own })
        } finally {
            resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "${MediaStore.Video.Media._ID} = ?", arrayOf(own.toString()))
        }
    }
}
