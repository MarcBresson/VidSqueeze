package app.squeeze.finalize

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import app.squeeze.data.BatchItemEntity
import app.squeeze.data.FinalizeState
import app.squeeze.data.ItemStatus
import app.squeeze.data.ResultsRepository
import app.squeeze.engine.FfmpegKitEngine
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
    suspend fun plan(batchId: Long): ReplacePlan = FinalizePlanner.replacePlan(results.items(batchId))

    /**
     * Originals the user has to approve before [replace] may overwrite them. With [trusted]
     * false only our own files count as approved (see [MediaStoreWriter.hasWriteAccess]).
     */
    suspend fun needsWriteConsent(batchId: Long, trusted: Boolean = true): List<Uri> =
        plan(batchId).itemsOf(ReplaceKind.IN_PLACE)
            .map { store.itemUri(it.mediaStoreId!!) }
            .filterNot { store.hasWriteAccess(it, trusted) }

    /** Adds every pending result to the gallery as a new video, next to its original. */
    suspend fun saveCopies(batchId: Long): FinalizeReport = withContext(Dispatchers.IO) {
        var report = FinalizeReport()
        for (item in results.pending(batchId)) {
            report = attempt(report, item) { saveCopy(item, report) }
        }
        report
    }

    /**
     * Replaces the originals with the pending results. Items that cannot be replaced (imported
     * ones) are saved as copies; originals we may not write yet are counted in
     * [FinalizeReport.needsConsent] and stay pending.
     */
    suspend fun replace(batchId: Long): FinalizeReport = withContext(Dispatchers.IO) {
        var report = FinalizeReport()
        for ((item, kind) in plan(batchId).items) {
            report = attempt(report, item) {
                when (kind) {
                    ReplaceKind.SAVE_COPY -> saveCopy(item, report)
                    ReplaceKind.IN_PLACE -> overwrite(item, report)
                    ReplaceKind.SWAP -> swap(item, report)
                }
            }
        }
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
        val original = item.mediaStoreId?.takeIf { !item.isImported }?.let(store::originalInfo)
        val uri = store.createCopy(
            staged, staged.name, original?.relativePath, FinalizePlanner.mimeType(staged.extension), original?.dateTakenMs,
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
        val uri = store.itemUri(item.mediaStoreId!!)
        store.overwrite(uri, staged)
        done(item, staged, FinalizeState.REPLACED, uri)
        return report.replacedWith(item)
    }

    private suspend fun swap(item: BatchItemEntity, report: FinalizeReport): FinalizeReport {
        val staged = verifiedStaged(item)
        val originalUri = store.itemUri(item.mediaStoreId!!)
        val original = store.originalInfo(item.mediaStoreId)
        val copy = store.createCopy(
            staged, FinalizePlanner.swappedName(item.displayName, staged.extension), original?.relativePath,
            FinalizePlanner.mimeType(staged.extension), original?.dateTakenMs,
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
                val original = store.itemUri(item.mediaStoreId)
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

    /** The staged file, after checking with ffprobe that it is a faithful copy of the source. */
    private suspend fun verifiedStaged(item: BatchItemEntity): File {
        val staged = stagedFile(item)
        val problem = OutputVerifier.problem(item.durationMs, engine.probe(staged.absolutePath, staged.extension))
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
        for (item in results.pending(batchId)) {
            item.stagedPath?.let { deleteStaged(File(it)) }
            results.markFinalized(item, FinalizeState.DISCARDED, null)
        }
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
        send.type = "video/*"
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
    }
}
