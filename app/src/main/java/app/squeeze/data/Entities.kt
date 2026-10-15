package app.squeeze.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.squeeze.engine.ExportMode

enum class BatchSource { SHARE, PICKER, AUTO }

enum class BatchStatus { QUEUED, RUNNING, AWAITING_ACTION, DONE, PARTIAL, CANCELLED }

enum class ItemStatus { QUEUED, RUNNING, DONE, FAILED, SKIPPED_NO_GAIN, CANCELLED }

enum class FinalizeState { PENDING, REPLACED, SAVED, SHARED_ONLY, DISCARDED }

/** One user action (share, pick, auto run). The preset is snapshotted so history survives preset edits. */
@Entity(tableName = "batch")
data class BatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: BatchSource,
    val presetId: String?,
    val presetName: String,
    val presetSnapshotJson: String,
    val exportMode: ExportMode,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val status: BatchStatus = BatchStatus.QUEUED,
)

@Entity(
    tableName = "batch_item",
    foreignKeys = [ForeignKey(BatchEntity::class, ["id"], ["batchId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("batchId"), Index("status")],
)
data class BatchItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    /** Position in the queue; items run in ascending order. */
    val position: Long,
    val sourceUri: String,
    val mediaStoreId: Long? = null,
    /** True when the source was copied into app storage (it cannot be replaced, only saved or shared). */
    val isImported: Boolean = false,
    val displayName: String,
    val originalSize: Long,
    val outputSize: Long? = null,
    val durationMs: Long = 0,
    val status: ItemStatus = ItemStatus.QUEUED,
    /** 0..1, or null while unknown. */
    val progress: Float? = null,
    val stagedPath: String? = null,
    val finalUri: String? = null,
    val finalizeState: FinalizeState = FinalizeState.PENDING,
    val ffmpegCommand: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val error: String? = null,
)

/** A user-made preset. Built-in presets live in code ([app.squeeze.engine.BuiltInPresets]), not here. */
@Entity(tableName = "preset")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val paramsJson: String,
    val exportJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)
