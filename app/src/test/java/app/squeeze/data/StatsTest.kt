package app.squeeze.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    private fun row(
        preset: String = "Share",
        id: String? = "share",
        original: Long = 1000,
        output: Long? = 400,
        state: FinalizeState = FinalizeState.SAVED,
        daysAgo: Long = 1,
    ) = StatRow(id, preset, original, output, state, now - daysAgo * day)

    @Test
    fun `totals add up and report saving and percent`() {
        val stats = StatsCalculator.compute(listOf(row(), row(original = 3000, output = 1600)), StatsPeriod.ALL, now)
        assertEquals(2, stats.total.videos)
        assertEquals(4000, stats.total.originalBytes)
        assertEquals(2000, stats.total.outputBytes)
        assertEquals(2000, stats.total.savedBytes)
        assertEquals(50, stats.total.savedPercent)
    }

    @Test
    fun `presets are never merged`() {
        val stats = StatsCalculator.compute(
            listOf(
                row("Share", "share"),
                row("Share (edited)", "share"),
                row("Share", "share"),
                row("Share", "other-id"),
            ),
            StatsPeriod.ALL, now,
        )
        assertEquals(3, stats.perPreset.size)
        assertEquals(2, stats.perPreset.single { it.presetName == "Share" && it.presetId == "share" }.totals.videos)
    }

    @Test
    fun `groups are ordered by what they saved`() {
        val stats = StatsCalculator.compute(
            listOf(row("Small", "a", original = 100, output = 90), row("Big", "b", original = 5000, output = 1000)),
            StatsPeriod.ALL, now,
        )
        assertEquals(listOf("Big", "Small"), stats.perPreset.map { it.presetName })
    }

    @Test
    fun `period keeps only recent compressions`() {
        val rows = listOf(row(daysAgo = 2), row(daysAgo = 20), row(daysAgo = 200), row(daysAgo = 400))
        fun count(p: StatsPeriod) = StatsCalculator.compute(rows, p, now).total.videos
        assertEquals(1, count(StatsPeriod.WEEK))
        assertEquals(2, count(StatsPeriod.MONTH))
        assertEquals(3, count(StatsPeriod.YEAR))
        assertEquals(4, count(StatsPeriod.ALL))
    }

    @Test
    fun `discarded results do not count and freed space only counts replaced originals`() {
        val stats = StatsCalculator.compute(
            listOf(
                row(state = FinalizeState.DISCARDED),
                row(state = FinalizeState.REPLACED, original = 1000, output = 100),
                row(state = FinalizeState.SAVED, original = 1000, output = 100),
            ),
            StatsPeriod.ALL, now,
        )
        assertEquals(2, stats.total.videos)
        assertEquals(1800, stats.total.savedBytes)
        assertEquals(900, stats.total.freedBytes)
    }

    @Test
    fun `empty input gives empty stats`() {
        val stats = StatsCalculator.compute(emptyList(), StatsPeriod.MONTH, now)
        assertEquals(0, stats.total.videos)
        assertEquals(0, stats.total.savedPercent)
        assertEquals(emptyList<PresetStats>(), stats.perPreset)
    }

    @Test
    fun `history shows newest batch first and hides batches with nothing to show`() {
        fun batch(id: Long, createdAt: Long) = BatchEntity(
            id = id, source = BatchSource.PICKER, presetId = null, presetName = "p", presetSnapshotJson = "{}",
            exportMode = app.squeeze.engine.ExportMode.ASK_WHEN_DONE, createdAt = createdAt,
        )
        fun item(batchId: Long, position: Long, status: ItemStatus) = BatchItemEntity(
            batchId = batchId, position = position, sourceUri = "content://x", displayName = "v$position.mp4",
            originalSize = 10, status = status,
        )
        val history = HistoryBuilder.build(
            listOf(batch(1, 100), batch(2, 300), batch(3, 200)),
            listOf(
                item(1, 1, ItemStatus.DONE),
                item(2, 3, ItemStatus.FAILED), item(2, 2, ItemStatus.DONE), item(2, 4, ItemStatus.CANCELLED),
                item(3, 5, ItemStatus.CANCELLED),
            ),
        )
        assertEquals(listOf(2L, 1L), history.map { it.batch.id })
        assertEquals(listOf(2L, 3L), history.first().items.map { it.position })
        assertNull(history.first().single)
        assertEquals(1, history.last().single?.position?.toInt())
    }
}
