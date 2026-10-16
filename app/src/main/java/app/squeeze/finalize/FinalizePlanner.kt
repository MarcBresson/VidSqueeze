package app.squeeze.finalize

import app.squeeze.data.BatchItemEntity
import app.squeeze.data.FinalizeState
import app.squeeze.data.ItemStatus
import app.squeeze.engine.Preset
import app.squeeze.engine.SourceInfo
import java.time.LocalDate

/** How "Replace originals" treats one staged result. */
enum class ReplaceKind {
    /** Same container: overwrite the original's bytes, keeping its URI, albums and favourites. */
    IN_PLACE,

    /** Different container: add the new file, then delete the original. */
    SWAP,

    /** Not a MediaStore video we may touch (shared from Drive, a messenger...): save a copy instead. */
    SAVE_COPY,
}

/** What a replace needs before it can run: which originals the user has to approve touching. */
data class ReplacePlan(val items: List<Pair<BatchItemEntity, ReplaceKind>>) {
    val isEmpty get() = items.isEmpty()
    fun kindOf(item: BatchItemEntity) = items.firstOrNull { it.first.id == item.id }?.second
    fun itemsOf(kind: ReplaceKind) = items.filter { it.second == kind }.map { it.first }
}

object FinalizePlanner {
    /** Results that still wait for a decision. */
    fun pending(items: List<BatchItemEntity>): List<BatchItemEntity> =
        items.filter { it.status == ItemStatus.DONE && it.finalizeState == FinalizeState.PENDING && it.stagedPath != null }

    fun replacePlan(items: List<BatchItemEntity>): ReplacePlan =
        ReplacePlan(pending(items).map { it to kindFor(it) })

    private fun kindFor(item: BatchItemEntity): ReplaceKind {
        if (item.isImported || item.mediaStoreId == null) return ReplaceKind.SAVE_COPY
        val stagedExtension = item.stagedPath?.substringAfterLast('.', "").orEmpty()
        return if (sameContainer(item.displayName, stagedExtension)) ReplaceKind.IN_PLACE else ReplaceKind.SWAP
    }

    /** True when a file named [originalName] can be overwritten with output that has [outputExtension]. */
    fun sameContainer(originalName: String, outputExtension: String): Boolean {
        val original = originalName.substringAfterLast('.', "").lowercase()
        val output = outputExtension.trimStart('.').lowercase()
        return original == output
    }

    /** "name.mov" replaced by an mp4 becomes "name.mp4". */
    fun swappedName(originalName: String, outputExtension: String): String =
        originalName.substringBeforeLast('.', originalName) + "." + outputExtension.trimStart('.')

    /**
     * The name a saved copy gets once it replaces its original: the original's name with the copy's
     * extension when [keepOriginalName], else the copy's own name.
     */
    fun replacementName(originalName: String, copyName: String, keepOriginalName: Boolean): String =
        if (keepOriginalName) swappedName(originalName, copyName.substringAfterLast('.', "")) else copyName

    fun resolutionLabel(maxShortSide: Int?): String = if (maxShortSide == null) "original" else "${maxShortSide}p"

    /** The name a saved copy gets, from the preset's template. */
    fun copyName(preset: Preset, originalName: String, outputExtension: String, today: LocalDate): String =
        NameTemplate.render(
            preset.export.nameTemplate,
            NameContext(
                originalFileName = originalName,
                outputFormat = outputExtension,
                presetName = preset.name,
                date = today,
                resolution = resolutionLabel(preset.params.maxShortSide),
            ),
        )

    fun mimeType(extension: String): String = when (extension.trimStart('.').lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        else -> "video/*"
    }
}

/** Checks a staged result before it overwrites anything. */
object OutputVerifier {
    const val DURATION_TOLERANCE_MS = 1_000L

    /** A human-readable problem, or null when [output] looks like a faithful copy of the source. */
    fun problem(expectedDurationMs: Long, output: SourceInfo?): String? = when {
        output == null -> "The compressed file can't be read."
        output.videoCodec == null || !output.dimensionsKnown -> "The compressed file has no video."
        expectedDurationMs > 0 && kotlin.math.abs(output.durationMs - expectedDurationMs) > DURATION_TOLERANCE_MS ->
            "The compressed file has a different length (${output.durationMs / 1000} s instead of ${expectedDurationMs / 1000} s)."
        else -> null
    }
}

/** What a finalize run did, for the notification and for tests. */
data class FinalizeReport(
    val replaced: Int = 0,
    val savedAsCopy: Int = 0,
    /** Originals that could not be touched without the user's approval; their results stay staged. */
    val needsConsent: Int = 0,
    val failures: List<String> = emptyList(),
    /** Original bytes of the items that were replaced or saved. */
    val originalBytes: Long = 0,
    val outputBytes: Long = 0,
    /** Originals whose replacement is in place and that still have to be deleted (needs the user's approval). */
    val originalsToDelete: List<String> = emptyList(),
) {
    val handled get() = replaced + savedAsCopy
    val savedBytes get() = (originalBytes - outputBytes).coerceAtLeast(0)
    val hasLeftovers get() = needsConsent > 0 || failures.isNotEmpty()
}

/** What [Finalizer.validateReplacement] did for one saved copy. */
sealed interface ReplaceAfterSaveResult {
    /** The original is gone and the copy now lives under [name]. */
    data class Done(val name: String) : ReplaceAfterSaveResult

    /** The user has to approve deleting [original] first; nothing was changed. */
    data class NeedsDeleteConsent(val original: android.net.Uri) : ReplaceAfterSaveResult

    /** Nothing to replace: the item was not saved as a copy of a library video, or was already handled. */
    data object Unavailable : ReplaceAfterSaveResult

    data class Failed(val message: String) : ReplaceAfterSaveResult
}
