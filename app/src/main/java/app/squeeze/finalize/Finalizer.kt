package app.squeeze.finalize

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.squeeze.data.BatchItemEntity
import app.squeeze.data.FinalizeState
import app.squeeze.data.ItemStatus
import app.squeeze.data.ResultsRepository
import app.squeeze.engine.FfmpegKitEngine
import app.squeeze.engine.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decides what happens to compressed files waiting in staging: save them next to the originals,
 * replace the originals, hand them to another app, or throw them away.
 *
 * A staged file is deleted only after its result is safely in place, so every operation can simply
 * be run again if it was interrupted or needed the user's approval first.
 */
@Singleton
class Finalizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val results: ResultsRepository,
    private val store: MediaStoreWriter,
    private val engine: FfmpegKitEngine,
) {
    /** Decides what happens to one staged result when replacing. */
    private suspend fun replaceOf(item: BatchItemEntity, report: FinalizeReport): FinalizeReport = when (FinalizePlanner.replaceKind(item)) {
        ReplaceKind.SAVE_COPY -> saveCopy(item, report)
        ReplaceKind.IN_PLACE -> overwrite(item, report)
        ReplaceKind.SWAP -> swap(item, report)
    }

    /** Saves one pending result as a copy next to its original. */
    suspend fun saveCopyItem(itemId: Long): FinalizeReport = withContext(Dispatchers.IO) {
        val item = pendingResult(itemId) ?: return@withContext FinalizeReport()
        attempt(FinalizeReport(), item) { saveCopy(item, FinalizeReport()) }
    }

    /** Replaces the original of one pending result, saving it as a copy when it cannot be replaced. */
    suspend fun replaceItem(itemId: Long): FinalizeReport = withContext(Dispatchers.IO) {
        val item = pendingResult(itemId) ?: return@withContext FinalizeReport()
        attempt(FinalizeReport(), item) { replaceOf(item, FinalizeReport()) }
    }

    /** Throws one pending result away. The original is untouched. */
    suspend fun discardItem(itemId: Long) = withContext(Dispatchers.IO) {
        val item = pendingResult(itemId) ?: return@withContext
        item.stagedPath?.let { deleteStaged(File(it)) }
        results.markFinalized(item, FinalizeState.DISCARDED, null)
    }

    /** A chooser that sends [itemId]'s result to another app; null when there is nothing to share. */
    suspend fun shareIntentItem(itemId: Long): Intent? = withContext(Dispatchers.IO) {
        val item = results.item(itemId) ?: return@withContext null
        // The staged file while the result is pending, the saved/replaced video once it is finalized.
        val pending = FinalizePlanner.pendingResult(item) != null
        val staged = if (pending) item.stagedPath?.let(::File)?.takeIf(File::isFile) else null
        val finalUri = if (!pending) item.finalUri?.toUri()?.takeIf { store.length(it) > 0 } else null
        if (staged == null && finalUri == null) return@withContext null
        val authority = "${context.packageName}.fileprovider"
        val uri = staged?.let { FileProvider.getUriForFile(context, authority, it) } ?: finalUri!!
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)
        send.type = FinalizePlanner.mimeType(staged?.extension ?: item.displayName.substringAfterLast('.', "mp4"))
        send.clipData = ClipData.newRawUri(null, uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Originals the user has to approve before [replaceItem] may overwrite them. */
    suspend fun needsWriteConsentItem(itemId: Long, trusted: Boolean = true): List<Uri> {
        val item = results.item(itemId) ?: return emptyList()
        if (FinalizePlanner.pendingResult(item) == null || FinalizePlanner.replaceKind(item) != ReplaceKind.IN_PLACE) return emptyList()
        val uri = store.itemUri(item.mediaStoreId!!, item.kind)
        return listOf(uri).filterNot { store.hasWriteAccess(it, trusted) }
    }

    /** The DONE item with a staged result still waiting for a decision, or null. */
    private suspend fun pendingResult(itemId: Long): BatchItemEntity? =
        results.item(itemId)?.let(FinalizePlanner::pendingResult)

    suspend fun plan(batchId: Long): ReplacePlan = FinalizePlanner.replacePlan(results.items(batchId))

    /**
     * Originals the user has to approve before [replace] may overwrite them. With [trusted]
     * false only our own files count as approved (see [MediaStoreWriter.hasWriteAccess]).
     */
    suspend fun needsWriteConsent(batchId: Long, trusted: Boolean = true): List<Uri> =
        plan(batchId).itemsOf(ReplaceKind.IN_PLACE)
            .map { store.itemUri(it.mediaStoreId!!, it.kind) }
            .filterNot { store.hasWriteAccess(it, trusted) }

    /** Adds every pending result to the gallery as a new video, next to its original. */
    suspend fun saveCopies(batchId: Long): FinalizeReport = withContext(Dispatchers.IO) {
        var report = FinalizeReport()
        for (item in results.pending(batchId)) report += saveCopyItem(item.id)
        report
    }

    /**
     * Replaces the originals with the pending results. Items that cannot be replaced (imported
     * ones) are saved as copies; originals we may not write yet are counted in
     * [FinalizeReport.needsConsent] and stay pending.
     */
    suspend fun replace(batchId: Long): FinalizeReport = withContext(Dispatchers.IO) {
        var report = FinalizeReport()
        for ((item, _) in plan(batchId).items) report += replaceItem(item.id)
        report
    }

    private suspend fun attempt(report: FinalizeReport, item: BatchItemEntity, block: suspend () -> FinalizeReport): FinalizeReport =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to ${item.displayName}", e)
            report.copy(needsConsent = report.needsConsent + 1)
        } catch (e: Exception) {
            Log.e(TAG, "Could not finalize ${item.displayName}", e)
            report.copy(failures = report.failures + "${item.displayName}: ${e.message ?: e.javaClass.simpleName}")
        }

    private suspend fun saveCopy(item: BatchItemEntity, report: FinalizeReport): FinalizeReport {
        val staged = stagedFile(item)
        val original = item.mediaStoreId?.takeIf { !item.isImported }?.let { store.originalInfo(it, item.kind) }
        val uri = store.createCopy(
            staged, staged.name, original?.relativePath, FinalizePlanner.mimeType(staged.extension), original?.dateTakenMs, item.kind,
        )
        done(item, staged, FinalizeState.SAVED, uri)
        return report.copy(
            savedAsCopy = report.savedAsCopy + 1,
            originalBytes = report.originalBytes + item.originalSize,
            outputBytes = report.outputBytes + (item.outputSize ?: item.originalSize),
        )
    }

    private suspend fun overwrite(item: BatchItemEntity, report: FinalizeReport): FinalizeReport {
        val staged = verifiedStaged(item)
        val uri = store.itemUri(item.mediaStoreId!!, item.kind)
        store.overwrite(uri, staged)
        done(item, staged, FinalizeState.REPLACED, uri)
        return report.replacedWith(item)
    }

    private suspend fun swap(item: BatchItemEntity, report: FinalizeReport): FinalizeReport {
        val staged = verifiedStaged(item)
        val originalUri = store.itemUri(item.mediaStoreId!!, item.kind)
        val original = store.originalInfo(item.mediaStoreId, item.kind)
        val copy = store.createCopy(
            staged, FinalizePlanner.swappedName(item.displayName, staged.extension), original?.relativePath,
            FinalizePlanner.mimeType(staged.extension), original?.dateTakenMs, item.kind,
        )
        done(item, staged, FinalizeState.REPLACED, copy)
        val next = report.replacedWith(item)
        return if (store.deleteQuietly(originalUri)) next else next.copy(originalsToDelete = next.originalsToDelete + originalUri.toString())
    }

    /**
     * Makes a saved copy take the place of its original: deletes the original, renames the copy
     * (see [FinalizePlanner.replacementName]) and records the item as replaced.
     *
     * Safe to call again: when the user's approval to delete is needed this returns
     * [ReplaceAfterSaveResult.NeedsDeleteConsent] without having changed anything, and a run that
     * got as far as deleting the original just carries on with the rename.
     */
    suspend fun validateReplacement(itemId: Long, keepOriginalName: Boolean): ReplaceAfterSaveResult = withContext(Dispatchers.IO) {
        val item = results.item(itemId)
        val copy = item?.finalUri?.let(Uri::parse)
        when {
            item == null || item.status != ItemStatus.DONE || item.finalizeState != FinalizeState.SAVED || copy == null ->
                ReplaceAfterSaveResult.Unavailable
            item.isImported || item.mediaStoreId == null -> ReplaceAfterSaveResult.Unavailable
            store.length(copy) <= 0 -> ReplaceAfterSaveResult.Failed("The saved copy is gone")
            else -> try {
                val original = store.itemUri(item.mediaStoreId, item.kind)
                if (store.exists(original) && !store.deleteQuietly(original)) {
                    ReplaceAfterSaveResult.NeedsDeleteConsent(original)
                } else {
                    val copyName = resolverName(copy)
                    val name = store.rename(copy, FinalizePlanner.replacementName(item.displayName, copyName, keepOriginalName))
                    results.markFinalized(item, FinalizeState.REPLACED, copy.toString())
                    ReplaceAfterSaveResult.Done(name)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not replace ${item.displayName} with its copy", e)
                ReplaceAfterSaveResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun resolverName(uri: Uri): String = store.displayName(uri) ?: throw IllegalStateException("The saved copy is gone")

    /**
     * The staged file, after checking that it is a faithful copy of the source. FFmpeg-written
     * outputs are re-probed; WebP and JPEG XL outputs were written by the platform / libjxl
     * encoders, which the bundled ffprobe cannot read, so only their existence and size are checked.
     */
    private suspend fun verifiedStaged(item: BatchItemEntity): File {
        val staged = stagedFile(item)
        if (item.kind == MediaKind.IMAGE && staged.extension.lowercase() in PLATFORM_IMAGE_EXTENSIONS) {
            if (staged.length() <= 0) throw IllegalStateException("The compressed image is empty.")
            return staged
        }
        val probed = engine.probe(staged.absolutePath, staged.extension)
        val problem = if (item.kind == MediaKind.IMAGE) OutputVerifier.imageProblem(probed)
        else OutputVerifier.problem(item.durationMs, probed)
        if (problem != null) throw IllegalStateException(problem)
        return staged
    }

    private fun stagedFile(item: BatchItemEntity): File {
        val staged = File(requireNotNull(item.stagedPath) { "No staged file" })
        check(staged.isFile) { "The compressed file is gone" }
        return staged
    }

    private suspend fun done(item: BatchItemEntity, staged: File, state: FinalizeState, finalUri: Uri?) {
        results.markFinalized(item, state, finalUri?.toString())
        deleteStaged(staged)
    }

    private fun FinalizeReport.replacedWith(item: BatchItemEntity) = copy(
        replaced = replaced + 1,
        originalBytes = originalBytes + item.originalSize,
        outputBytes = outputBytes + (item.outputSize ?: item.originalSize),
    )

    /** Throws the pending results of [batchId] away. The originals are untouched. */
    suspend fun discard(batchId: Long) = withContext(Dispatchers.IO) {
        results.pending(batchId).forEach { discardItem(it.id) }
    }

    /** Discards results that waited longer than [maxAgeMs]. Returns the batches cleaned. */
    suspend fun discardOlderThan(maxAgeMs: Long, now: Long = System.currentTimeMillis()): List<Long> {
        val expired = results.awaitingBefore(now - maxAgeMs)
        expired.forEach { discard(it.id) }
        return expired.map { it.id }
    }

    /** Discards every result still waiting for a decision, e.g. from "Clear" in Settings. Originals are untouched. */
    suspend fun discardAllPending(now: Long = System.currentTimeMillis()): List<Long> = discardOlderThan(0, now + 1)

    /**
     * Removes folders under [stagingDir] that hold no files (left behind by cancelled or finished
     * items). Folders touched in the last minute are left alone: the queue may be about to encode into one.
     */
    suspend fun pruneEmptyStagingDirs(stagingDir: File, now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        // Ages are read before anything is deleted: removing an item folder touches its batch folder.
        // Children come first, so a batch folder emptied by its item folders goes in the same pass.
        val touched = stagingDir.walkBottomUp().filter { it != stagingDir && it.isDirectory }.map { it to it.lastModified() }.toList()
        touched.forEach { (dir, modified) -> if (now - modified > 60_000 && dir.list().isNullOrEmpty()) dir.delete() }
    }

    /** A chooser that sends the pending results of [batchId] to another app; null when there are none. */
    suspend fun shareIntent(batchId: Long): Intent? = withContext(Dispatchers.IO) {
        val files = results.pending(batchId).mapNotNull { it.stagedPath?.let(::File)?.takeIf(File::isFile) }
        if (files.isEmpty()) return@withContext null
        val authority = "${context.packageName}.fileprovider"
        val uris = files.map { FileProvider.getUriForFile(context, authority, it) }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        // A mixed batch shares as video/* (the common case); an all-photo batch as image/*.
        send.type = if (files.all { it.extension.lowercase() in IMAGE_EXTENSIONS }) "image/*" else "video/*"
        send.clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun deleteStaged(file: File) {
        file.delete()
        // Staged files live in staging/<batch>/<item>/; drop the folders once they are empty.
        file.parentFile?.let { dir -> if (dir.delete()) dir.parentFile?.delete() }
    }

    private companion object {
        const val TAG = "Finalizer"

        /** Image outputs ffprobe cannot decode (written by the platform / libjxl encoders). */
        val PLATFORM_IMAGE_EXTENSIONS = setOf("webp", "jxl")

        /** Every image extension this app can output; a share of only these is a photo share. */
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "avif", "webp", "jxl")
    }
}
