package app.squeeze.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BatchDao {
    @Insert
    suspend fun insert(batch: BatchEntity): Long

    @Update
    suspend fun update(batch: BatchEntity)

    @Query("SELECT * FROM batch WHERE id = :id")
    suspend fun get(id: Long): BatchEntity?

    @Query("SELECT * FROM batch ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BatchEntity>>

    @Query("UPDATE batch SET status = :status, startedAt = COALESCE(startedAt, :now) WHERE id = :id")
    suspend fun markStarted(id: Long, status: BatchStatus, now: Long)

    @Query("UPDATE batch SET status = :status, finishedAt = :now WHERE id = :id")
    suspend fun markFinished(id: Long, status: BatchStatus, now: Long)

    @Query("DELETE FROM batch WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM batch WHERE status = 'AWAITING_ACTION' ORDER BY createdAt DESC")
    fun observeAwaiting(): Flow<List<BatchEntity>>

    @Query("SELECT * FROM batch WHERE status = 'AWAITING_ACTION' AND COALESCE(finishedAt, createdAt) < :cutoff")
    suspend fun awaitingBefore(cutoff: Long): List<BatchEntity>

    @Query("SELECT * FROM batch WHERE status NOT IN ('QUEUED', 'RUNNING')")
    fun observeFinished(): Flow<List<BatchEntity>>

    /** Unlike [markFinished] this keeps `finishedAt`, the end of the compression. */
    @Query("UPDATE batch SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: BatchStatus)
}

@Dao
interface BatchItemDao {
    @Insert
    suspend fun insertAll(items: List<BatchItemEntity>): List<Long>

    @Update
    suspend fun update(item: BatchItemEntity)

    @Query("SELECT * FROM batch_item WHERE id = :id")
    suspend fun get(id: Long): BatchItemEntity?

    @Query("SELECT * FROM batch_item WHERE batchId = :batchId ORDER BY position")
    suspend fun forBatch(batchId: Long): List<BatchItemEntity>

    /** Items the queue still has to deal with, in run order. */
    @Query("SELECT * FROM batch_item WHERE status IN ('QUEUED', 'RUNNING') ORDER BY position")
    fun observeActive(): Flow<List<BatchItemEntity>>

    @Query("SELECT * FROM batch_item WHERE status = 'QUEUED' ORDER BY position LIMIT 1")
    suspend fun nextQueued(): BatchItemEntity?

    @Query("SELECT COUNT(*) FROM batch_item WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun activeCount(): Int

    @Query("SELECT COALESCE(MAX(position), 0) FROM batch_item")
    suspend fun maxPosition(): Long

    @Query("UPDATE batch_item SET status = :status, startedAt = :now, progress = NULL, error = NULL WHERE id = :id")
    suspend fun markRunning(id: Long, status: ItemStatus, now: Long)

    @Query("UPDATE batch_item SET progress = :progress WHERE id = :id AND status = 'RUNNING'")
    suspend fun setProgress(id: Long, progress: Float?)

    /** Crash and timeout recovery: a RUNNING row has no live encoder behind it. */
    @Query("UPDATE batch_item SET status = 'QUEUED', progress = NULL WHERE status = 'RUNNING'")
    suspend fun requeueRunning(): Int

    @Query("UPDATE batch_item SET status = 'CANCELLED', finishedAt = :now WHERE status = 'QUEUED'")
    suspend fun cancelQueued(now: Long): Int

    @Query("UPDATE batch_item SET status = 'CANCELLED', finishedAt = :now WHERE id = :id AND status = 'QUEUED'")
    suspend fun cancelQueued(id: Long, now: Long): Int

    @Query("SELECT DISTINCT batchId FROM batch_item WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun activeBatchIds(): List<Long>

    @Query("SELECT COUNT(*) FROM batch_item WHERE batchId = :batchId AND status IN ('QUEUED', 'RUNNING')")
    suspend fun activeInBatch(batchId: Long): Int

    @Query("SELECT * FROM batch_item WHERE status NOT IN ('QUEUED', 'RUNNING')")
    fun observeFinished(): Flow<List<BatchItemEntity>>

    /** Compressed videos finished since [since], with the preset their batch ran under. */
    @Query(
        "SELECT b.presetId AS presetId, b.presetName AS presetName, i.originalSize AS originalSize, " +
            "i.outputSize AS outputSize, i.finalizeState AS finalizeState, i.finishedAt AS finishedAt " +
            "FROM batch_item i JOIN batch b ON b.id = i.batchId " +
            "WHERE i.status = 'DONE' AND i.finalizeState != 'DISCARDED' AND i.finishedAt >= :since",
    )
    fun observeStatRows(since: Long): Flow<List<StatRow>>

    /** Library videos Squeeze has compressed, is compressing, or decided not to touch (no gain). */
    @Query(
        "SELECT DISTINCT mediaStoreId FROM batch_item WHERE mediaStoreId IS NOT NULL " +
            "AND status IN ('DONE', 'SKIPPED_NO_GAIN', 'QUEUED', 'RUNNING')",
    )
    suspend fun handledMediaIds(): List<Long>

    /** Videos that failed at least [times] times: retrying them every night would be pointless. */
    @Query("SELECT mediaStoreId FROM batch_item WHERE mediaStoreId IS NOT NULL AND status = 'FAILED' GROUP BY mediaStoreId HAVING COUNT(*) >= :times")
    suspend fun failedAtLeast(times: Int): List<Long>

    /** Where results ended up; those files are our own output and must not be compressed again. */
    @Query("SELECT finalUri FROM batch_item WHERE finalUri IS NOT NULL")
    suspend fun finalUris(): List<String>

    @Query(
        "SELECT DISTINCT batchId FROM batch_item WHERE status = 'QUEUED' " +
            "AND batchId IN (SELECT id FROM batch WHERE source = :source)",
    )
    suspend fun queuedBatchIdsOf(source: BatchSource): List<Long>

    @Query(
        "UPDATE batch_item SET status = 'CANCELLED', finishedAt = :now WHERE status = 'QUEUED' " +
            "AND batchId IN (SELECT id FROM batch WHERE source = :source)",
    )
    suspend fun cancelQueuedOf(source: BatchSource, now: Long): Int

    /** Compressed results nobody has replaced, saved or discarded yet. */
    @Query("SELECT * FROM batch_item WHERE status = 'DONE' AND finalizeState = 'PENDING' ORDER BY position")
    fun observePendingResults(): Flow<List<BatchItemEntity>>
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM preset ORDER BY createdAt")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM preset ORDER BY createdAt")
    suspend fun all(): List<PresetEntity>

    @Query("SELECT * FROM preset WHERE id = :id")
    suspend fun get(id: String): PresetEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: PresetEntity)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertAll(presets: List<PresetEntity>)

    @Query("DELETE FROM preset WHERE id = :id")
    suspend fun delete(id: String)
}
