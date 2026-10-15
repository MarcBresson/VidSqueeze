package app.squeeze.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.squeeze.engine.BuiltInPresets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: SqueezeDatabase
    private lateinit var repo: QueueRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SqueezeDatabase::class.java).allowMainThreadQueries().build()
        repo = QueueRepository(db.batchDao(), db.batchItemDao())
    }

    @After
    fun tearDown() = db.close()

    private fun src(name: String) = QueuedSource("file:///$name", null, false, name, 1000)

    @Test
    fun itemsRunInEnqueueOrderAcrossBatches() = runBlocking {
        repo.enqueue(BatchSource.PICKER, BuiltInPresets.share, listOf(src("a"), src("b")))
        repo.enqueue(BatchSource.SHARE, BuiltInPresets.balanced, listOf(src("c")))

        val names = mutableListOf<String>()
        while (true) {
            val next = repo.nextQueued() ?: break
            names += next.displayName
            repo.markRunning(next)
            repo.complete(next.id, ItemResult.Cancelled)
        }
        assertEquals(listOf("a", "b", "c"), names)
    }

    @Test
    fun batchSnapshotsThePreset() = runBlocking {
        val id = repo.enqueue(BatchSource.SHARE, BuiltInPresets.maxSavings, listOf(src("a")))
        val batch = requireNotNull(repo.batch(id))
        assertEquals(BuiltInPresets.maxSavings.name, batch.presetName)
        assertEquals(BuiltInPresets.MAX_SAVINGS_ID, batch.presetId)
        assert(batch.presetSnapshotJson.contains("\"maxShortSide\":720"))
    }

    @Test
    fun completingTheLastItemFinishesTheBatchOnce() = runBlocking {
        val id = repo.enqueue(BatchSource.PICKER, BuiltInPresets.share, listOf(src("a"), src("b")))
        val (a, b) = db.batchItemDao().forBatch(id)

        assertNull(repo.complete(a.id, ItemResult.Done("/staged/a.mp4", 400, "ffmpeg", 5_000)))
        val finished = requireNotNull(repo.complete(b.id, ItemResult.NoGain(1200, "ffmpeg", 5_000)))

        assertEquals(id, finished.batchId)
        assertEquals(BatchStatus.AWAITING_ACTION, finished.status)
        assertEquals(1, finished.summary.done)
        assertEquals(1, finished.summary.skippedNoGain)
        assertEquals(60, finished.summary.savedPercent)
        assertNotNull(repo.batch(id)!!.finishedAt)
    }

    @Test
    fun cancelAllQueuedLeavesTheRunningItem() = runBlocking {
        val id = repo.enqueue(BatchSource.PICKER, BuiltInPresets.share, listOf(src("a"), src("b"), src("c")))
        repo.markRunning(repo.nextQueued()!!)

        val finished = repo.cancelQueued()

        assertEquals(emptyList<FinishedBatch>(), finished) // the running item keeps the batch open
        assertEquals(1, repo.activeCount())
        val statuses = db.batchItemDao().forBatch(id).map { it.status }
        assertEquals(listOf(ItemStatus.RUNNING, ItemStatus.CANCELLED, ItemStatus.CANCELLED), statuses)
    }

    @Test
    fun removingAQueuedItemCancelsOnlyThatOne() = runBlocking {
        val id = repo.enqueue(BatchSource.PICKER, BuiltInPresets.share, listOf(src("a"), src("b")))
        val (a, b) = db.batchItemDao().forBatch(id)

        assertNull(repo.cancelQueued(a.id))
        assertEquals(ItemStatus.CANCELLED, repo.item(a.id)!!.status)
        assertEquals(ItemStatus.QUEUED, repo.item(b.id)!!.status)
        assertEquals(BatchStatus.CANCELLED, repo.cancelQueued(b.id)!!.status)
    }
}
