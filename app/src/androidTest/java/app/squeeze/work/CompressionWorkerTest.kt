package app.squeeze.work

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.squeeze.auto.DeviceStateReader
import app.squeeze.data.AutoSettingsRepository
import app.squeeze.data.BatchSource
import app.squeeze.data.RunLimits
import app.squeeze.data.BatchStatus
import app.squeeze.data.FinalizeState
import app.squeeze.data.ItemStatus
import app.squeeze.data.QueueRepository
import app.squeeze.data.QueuedSource
import app.squeeze.data.ResultsRepository
import app.squeeze.data.SqueezeDatabase
import app.squeeze.engine.BuiltInPresets
import app.squeeze.engine.EncoderPreset
import app.squeeze.engine.ExportMode
import app.squeeze.engine.FfmpegKitEngine
import app.squeeze.engine.HardwareEncoderProbe
import app.squeeze.engine.Preset
import app.squeeze.engine.RunResult
import app.squeeze.finalize.BatchCompletion
import app.squeeze.finalize.Finalizer
import app.squeeze.finalize.MediaStoreWriter
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs [CompressionWorker] against an in-memory database and the real FFmpegKit on a device. */
@RunWith(AndroidJUnit4::class)
class CompressionWorkerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = FfmpegKitEngine(context)
    private lateinit var db: SqueezeDatabase
    private lateinit var repository: QueueRepository
    private lateinit var processor: ItemProcessor
    private lateinit var current: CurrentJob
    private lateinit var dir: File

    /** The share preset, but with a fast encoder so the tests stay quick. */
    private val preset: Preset = BuiltInPresets.share.copy(
        params = BuiltInPresets.share.params.copy(encoderPreset = EncoderPreset.ULTRAFAST),
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SqueezeDatabase::class.java).allowMainThreadQueries().build()
        repository = QueueRepository(db.batchDao(), db.batchItemDao())
        current = CurrentJob(engine)
        processor = ItemProcessor(context, engine, current, HardwareEncoderProbe())
        dir = File(context.cacheDir, "worker-test").apply { deleteRecursively(); mkdirs() }
        processor.stagingDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
        processor.stagingDir.deleteRecursively()
    }

    private fun worker(): CompressionWorker = TestListenableWorkerBuilder<CompressionWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                val notifications = NotificationHelper(appContext)
                val results = ResultsRepository(db.batchDao(), db.batchItemDao())
                val store = MediaStoreWriter(appContext)
                val autoSettings = AutoSettingsRepository(appContext)
                val completion = BatchCompletion(results, Finalizer(appContext, results, store, engine), store, notifications)
                return CompressionWorker(
                    appContext, workerParameters, repository, processor, notifications, completion, current,
                    autoSettings, DeviceStateReader(appContext),
                )
            }
        })
        .build()

    private fun clip(name: String, seconds: Int = 2, size: String = "1920x1080", extra: List<String> = emptyList()): File {
        val out = File(dir, name)
        val args = listOf(
            "-y", "-f", "lavfi", "-i", "testsrc2=size=$size:rate=30:duration=$seconds",
            "-f", "lavfi", "-i", "sine=duration=$seconds",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
        ) + extra + out.absolutePath
        assertEquals(RunResult.Success, runBlocking { engine.run(args, seconds * 1000L) })
        return out
    }

    private fun source(file: File, size: Long = file.length()) =
        QueuedSource(Uri.fromFile(file).toString(), null, isImported = false, displayName = file.name, size = size)

    private suspend fun items(batchId: Long) = db.batchItemDao().forBatch(batchId)

    @Test
    fun saveCopiesModeFinalizesWithoutAsking() = runBlocking {
        val big = clip("auto-save.mp4")
        val saving = preset.copy(export = preset.export.copy(mode = ExportMode.SAVE_COPIES))
        val batchId = repository.enqueue(BatchSource.AUTO, saving, listOf(source(big)))

        assertTrue(worker().doWork() is ListenableWorker.Result.Success)

        val item = items(batchId).single()
        assertEquals(ItemStatus.DONE, item.status)
        assertEquals(FinalizeState.SAVED, item.finalizeState)
        assertNull(item.stagedPath)
        val saved = Uri.parse(requireNotNull(item.finalUri))
        try {
            assertEquals("auto-save - compressed.mp4", context.contentResolver.query(saved, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null })
        } finally {
            context.contentResolver.delete(saved, null, null)
        }
        assertEquals(BatchStatus.DONE, requireNotNull(repository.batch(batchId)).status)
    }

    @Test
    fun autoItemsPastTheirDeadlineAreCancelledNotEncoded() = runBlocking {
        val settings = AutoSettingsRepository(context)
        settings.setRunLimits(RunLimits(deadlineMs = System.currentTimeMillis() - 1_000, gates = null))
        try {
            val auto = repository.enqueue(BatchSource.AUTO, preset, listOf(source(clip("night.mp4"))))
            val manual = repository.enqueue(BatchSource.PICKER, preset, listOf(source(clip("day.mp4"))))

            assertTrue(worker().doWork() is ListenableWorker.Result.Success)

            assertEquals(ItemStatus.CANCELLED, items(auto).single().status)
            assertNull(items(auto).single().stagedPath)
            assertEquals("the window's end must not hold back what the user queued", ItemStatus.DONE, items(manual).single().status)
            assertEquals(BatchStatus.CANCELLED, requireNotNull(repository.batch(auto)).status)
        } finally {
            settings.setRunLimits(null)
        }
    }

    @Test
    fun autoItemsRunWhileTheirLimitsAllow() = runBlocking {
        val settings = AutoSettingsRepository(context)
        settings.setRunLimits(RunLimits(deadlineMs = System.currentTimeMillis() + 600_000, gates = null))
        try {
            val auto = repository.enqueue(BatchSource.AUTO, preset, listOf(source(clip("night2.mp4"))))
            assertTrue(worker().doWork() is ListenableWorker.Result.Success)
            assertEquals(ItemStatus.DONE, items(auto).single().status)
            val log = LogStore(context).read(items(auto).single().id)
            assertEquals("the log starts with the command", items(auto).single().ffmpegCommand, log?.command)
            assertTrue("ffmpeg's output is kept", log?.output?.isNotBlank() == true)
        } finally {
            settings.setRunLimits(null)
        }
    }

    @Test
    fun compressesQueueAndRecordsOutcomes() = runBlocking {
        val big = clip("big.mp4")
        // Already tiny: re-encoding at CRF 28 cannot beat it.
        val tiny = clip("tiny.mp4", seconds = 1, size = "160x90", extra = listOf("-b:v", "20k", "-b:a", "16k"))
        val missing = File(dir, "missing.mp4")
        val batchId = repository.enqueue(
            BatchSource.PICKER, preset,
            listOf(source(big), source(tiny), source(missing, size = 1234)),
        )

        val result = worker().doWork()
        assertTrue(result is ListenableWorker.Result.Success)

        val (done, noGain, failed) = items(batchId)
        assertEquals(ItemStatus.DONE, done.status)
        val staged = File(requireNotNull(done.stagedPath))
        assertTrue(staged.exists())
        assertEquals(staged.length(), done.outputSize)
        assertTrue("staged ${staged.length()} vs ${big.length()}", staged.length() < big.length())
        assertNotNull(done.ffmpegCommand)
        assertTrue(done.durationMs in 1_500..2_500)
        assertEquals(1f, done.progress)

        assertEquals(ItemStatus.SKIPPED_NO_GAIN, noGain.status)
        assertNull(noGain.stagedPath)
        assertEquals(1, processor.stagingDir.walkTopDown().count { it.isFile })

        assertEquals(ItemStatus.FAILED, failed.status)
        assertNotNull(failed.error)

        val batch = requireNotNull(repository.batch(batchId))
        assertEquals(BatchStatus.AWAITING_ACTION, batch.status)
        assertNotNull(batch.finishedAt)
        assertEquals(0, repository.activeCount())
    }

    @Test
    fun resumesItemsLeftRunningByADeadWorker() = runBlocking {
        val batchId = repository.enqueue(BatchSource.SHARE, preset, listOf(source(clip("a.mp4"))))
        val stuck = items(batchId).single()
        repository.markRunning(stuck) // as if the process died mid-encode

        worker().doWork()

        assertEquals(ItemStatus.DONE, items(batchId).single().status)
    }

    @Test
    fun cancellingCurrentItemMovesOnToTheNext() = runBlocking {
        // A long, slow encode so there is time to cancel it.
        val slow = clip("slow.mp4", seconds = 30, extra = listOf("-g", "5"))
        val quick = clip("quick.mp4")
        val slowPreset = preset.copy(params = preset.params.copy(encoderPreset = EncoderPreset.VERYSLOW, maxShortSide = null))
        val first = repository.enqueue(BatchSource.PICKER, slowPreset, listOf(source(slow)))
        val second = repository.enqueue(BatchSource.PICKER, preset, listOf(source(quick)))

        val run = async(start = CoroutineStart.DEFAULT) { worker().doWork() }
        withTimeout(60_000) {
            while (items(first).single().progress == null || items(first).single().status != ItemStatus.RUNNING) delay(200)
        }
        current.requestCancel()
        run.await()

        assertEquals(ItemStatus.CANCELLED, items(first).single().status)
        assertEquals(BatchStatus.CANCELLED, repository.batch(first)!!.status)
        assertEquals(ItemStatus.DONE, items(second).single().status)
        assertFalse(File(processor.stagingDir, "$first").walkTopDown().any { it.isFile })
    }

    @Test
    fun stoppingTheWorkerPutsTheItemBackInTheQueue() = runBlocking {
        val slow = clip("slow2.mp4", seconds = 30, extra = listOf("-g", "5"))
        val slowPreset = preset.copy(params = preset.params.copy(encoderPreset = EncoderPreset.VERYSLOW, maxShortSide = null))
        val batchId = repository.enqueue(BatchSource.PICKER, slowPreset, listOf(source(slow)))

        val run = async { worker().doWork() }
        withTimeout(60_000) { while (items(batchId).single().status != ItemStatus.RUNNING) delay(200) }
        run.cancel()
        runCatching { run.await() }

        val item = items(batchId).single()
        assertEquals(ItemStatus.QUEUED, item.status)
        assertNull(item.progress)
        assertFalse(File(processor.stagingDir, "$batchId").walkTopDown().any { it.isFile })
        assertEquals(1, repository.activeCount())
    }
}
