package app.squeeze.data

import app.squeeze.engine.EngineJson
import app.squeeze.engine.Preset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString

/** A source ready to be queued (already resolved to a stable URI, see [SourceImporter]). */
data class QueuedSource(
    val uri: String,
    val mediaStoreId: Long?,
    val isImported: Boolean,
    val displayName: String,
    val size: Long,
)

/** What happened to one item, as recorded by [QueueRepository.complete]. */
sealed interface ItemResult {
    data class Done(val stagedPath: String, val outputSize: Long, val command: String, val durationMs: Long) : ItemResult
    data class NoGain(val outputSize: Long, val command: String, val durationMs: Long) : ItemResult
    data class Failed(val message: String, val command: String?) : ItemResult
    data object Cancelled : ItemResult
}

@Singleton
class QueueRepository @Inject constructor(
    private val batches: BatchDao,
    private val items: BatchItemDao,
) {
    private companion object {
        const val MAX_FAILURES = 2
    }

    internal var clock: () -> Long = System::currentTimeMillis

    fun observeActive(): Flow<List<BatchItemEntity>> = items.observeActive()

    suspend fun enqueue(source: BatchSource, preset: Preset, sources: List<QueuedSource>): Long {
        require(sources.isNotEmpty()) { "Nothing to enqueue" }
        val batchId = batches.insert(
            BatchEntity(
                source = source,
                presetId = preset.id,
                presetName = preset.name,
                presetSnapshotJson = EngineJson.encodeToString(preset),
                exportMode = preset.export.mode,
                createdAt = clock(),
            ),
        )
        var position = items.maxPosition()
        items.insertAll(
            sources.map {
                BatchItemEntity(
                    batchId = batchId,
                    position = ++position,
                    sourceUri = it.uri,
                    mediaStoreId = it.mediaStoreId,
                    isImported = it.isImported,
                    displayName = it.displayName,
                    originalSize = it.size,
                )
            },
        )
        return batchId
    }

    suspend fun activeCount(): Int = items.activeCount()

    suspend fun nextQueued(): BatchItemEntity? = items.nextQueued()

    suspend fun batch(id: Long): BatchEntity? = batches.get(id)

    suspend fun item(id: Long): BatchItemEntity? = items.get(id)


    suspend fun isAutoItem(itemId: Long): Boolean =
        items.get(itemId)?.let { batches.get(it.batchId)?.source == BatchSource.AUTO } == true

    suspend fun markRunning(item: BatchItemEntity) {
        val now = clock()
        batches.markStarted(item.batchId, BatchStatus.RUNNING, now)
        items.markRunning(item.id, ItemStatus.RUNNING, now)
    }

    suspend fun setProbed(itemId: Long, durationMs: Long) {
        items.get(itemId)?.let { items.update(it.copy(durationMs = durationMs)) }
    }

    suspend fun setProgress(itemId: Long, progress: Float?) = items.setProgress(itemId, progress)

    /** Puts items a dead worker left in RUNNING back in the queue. Call when a worker starts. */
    suspend fun recoverInterrupted(): Int = items.requeueRunning()

    /**
     * Records the outcome of [itemId]. Returns the batch summary when this was the batch's last
     * active item (so the caller can post the completion notification), otherwise null.
     */
    suspend fun complete(itemId: Long, result: ItemResult): FinishedBatch? {
        val item = items.get(itemId) ?: return null
        val now = clock()
        val updated = when (result) {
            is ItemResult.Done -> item.copy(
                status = ItemStatus.DONE, outputSize = result.outputSize, stagedPath = result.stagedPath,
                ffmpegCommand = result.command, durationMs = result.durationMs, progress = 1f, error = null,
            )
            is ItemResult.NoGain -> item.copy(
                status = ItemStatus.SKIPPED_NO_GAIN, outputSize = result.outputSize,
                ffmpegCommand = result.command, durationMs = result.durationMs, progress = null,
            )
            is ItemResult.Failed -> item.copy(
                status = ItemStatus.FAILED, error = result.message, ffmpegCommand = result.command, progress = null,
            )
            ItemResult.Cancelled -> item.copy(status = ItemStatus.CANCELLED, progress = null)
        }.copy(finishedAt = now)
        items.update(updated)
        return finishBatchIfOver(item.batchId, now)
    }

    /** Cancels everything still waiting. A running item is stopped separately by the caller. */
    suspend fun cancelQueued(): List<FinishedBatch> {
        val touched = items.activeBatchIds()
        items.cancelQueued(clock())
        return touched.mapNotNull { finishBatchIfOver(it, clock()) }
    }

    suspend fun cancelQueued(itemId: Long): FinishedBatch? {
        val item = items.get(itemId) ?: return null
        if (items.cancelQueued(itemId, clock()) == 0) return null
        return finishBatchIfOver(item.batchId, clock())
    }

    /** Cancels what auto compression still has waiting (window over, charger unplugged, stop command). */
    suspend fun cancelQueuedAuto(): List<FinishedBatch> {
        val touched = items.queuedBatchIdsOf(BatchSource.AUTO)
        if (touched.isEmpty()) return emptyList()
        items.cancelQueuedOf(BatchSource.AUTO, clock())
        return touched.mapNotNull { finishBatchIfOver(it, clock()) }
    }

    /** Library video ids that automatic runs and suggestions must leave alone. */
    suspend fun skipIds(): Set<Long> {
        val ids = items.handledMediaIds().toMutableSet()
        ids += items.failedAtLeast(MAX_FAILURES)
        items.finalUris().mapNotNullTo(ids) { SourceImporter.mediaStoreVideoId(android.net.Uri.parse(it)) }
        return ids
    }

    private suspend fun finishBatchIfOver(batchId: Long, now: Long): FinishedBatch? {
        if (items.activeInBatch(batchId) > 0) return null
        val all = items.forBatch(batchId)
        val status = BatchSummary.statusFor(all.map { it.status })
        batches.markFinished(batchId, status, now)
        return FinishedBatch(batchId, status, BatchSummary.of(all))
    }
}

data class FinishedBatch(val batchId: Long, val status: BatchStatus, val summary: BatchSummary)
