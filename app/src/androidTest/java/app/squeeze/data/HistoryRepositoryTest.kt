package app.squeeze.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.squeeze.engine.BuiltInPresets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: SqueezeDatabase
    private lateinit var queue: QueueRepository
    private lateinit var history: HistoryRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SqueezeDatabase::class.java).allowMainThreadQueries().build()
        queue = QueueRepository(db.batchDao(), db.batchItemDao())
        history = HistoryRepository(db.batchDao(), db.batchItemDao())
    }

    @After
    fun tearDown() = db.close()

    private fun src(name: String) = QueuedSource("file:///$name", null, false, name, 1000)

    private suspend fun finish(batchId: Long, vararg results: ItemResult) {
        db.batchItemDao().forBatch(batchId).zip(results).forEach { (item, result) ->
            queue.markRunning(item)
            queue.complete(item.id, result)
        }
    }

    @Test
    fun historyListsFinishedBatchesOnly() = runBlocking {
        val finished = queue.enqueue(BatchSource.PICKER, BuiltInPresets.share, listOf(src("a"), src("b")))
        finish(finished, ItemResult.Cancelled, ItemResult.Cancelled)
        queue.enqueue(BatchSource.PICKER, BuiltInPresets.share, listOf(src("c")))

        val noneYet = history.observeHistory().first()
        assertEquals(emptyList<Long>(), noneYet.map { it.batch.id }) // all cancelled: nothing to show

        val failed = queue.enqueue(BatchSource.SHARE, BuiltInPresets.balanced, listOf(src("d")))
        finish(failed, ItemResult.Failed("boom", null))
        val entries = history.observeHistory().first()
        assertEquals(listOf(failed), entries.map { it.batch.id })
        assertEquals("boom", entries.single().single?.error)
        assertEquals(1, db.batchItemDao().activeCount())
    }

    @Test
    fun statsJoinTheBatchPresetAndSkipDiscardedAndUnfinished() = runBlocking {
        val a = queue.enqueue(BatchSource.PICKER, BuiltInPresets.share, listOf(src("a"), src("b")))
        finish(a, ItemResult.Done("/x/a", 400, "cmd", 1000), ItemResult.Done("/x/b", 300, "cmd", 1000))
        val discarded = db.batchItemDao().forBatch(a).last()
        db.batchItemDao().update(discarded.copy(finalizeState = FinalizeState.DISCARDED))
        val b = queue.enqueue(BatchSource.PICKER, BuiltInPresets.balanced, listOf(src("c")))
        finish(b, ItemResult.Done("/x/c", 500, "cmd", 1000))
        queue.enqueue(BatchSource.PICKER, BuiltInPresets.balanced, listOf(src("d"))) // still queued

        val stats = history.observeStats(StatsPeriod.WEEK).first()
        assertEquals(2, stats.total.videos)
        assertEquals(2, stats.perPreset.size)
        assertEquals(setOf(BuiltInPresets.share.name, BuiltInPresets.balanced.name), stats.perPreset.map { it.presetName }.toSet())
        assertEquals(600, stats.perPreset.single { it.presetName == BuiltInPresets.share.name }.totals.savedBytes)
    }
}
