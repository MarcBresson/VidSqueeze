package app.squeeze.work

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import javax.inject.Inject
import javax.inject.Singleton

/** What the "Show logs" sheet displays: the ffmpeg command and everything ffmpeg printed. */
data class ItemLog(val command: String, val output: String)

/**
 * ffmpeg logs of finished items, one text file per item under `files/logs`. The first line is the
 * command, the rest is ffmpeg's output, cut at [maxBytes] so one chatty encode cannot fill the phone.
 */
@Singleton
class LogStore(private val dir: File, private val maxBytes: Long) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "logs"), MAX_BYTES)

    private fun file(itemId: Long) = File(dir, "$itemId.log")

    fun exists(itemId: Long): Boolean = file(itemId).isFile

    /** Starts a fresh log for [itemId] whose first line is [command]. Call [LogWriter.close] when done. */
    fun open(itemId: Long, command: String): LogWriter {
        dir.mkdirs()
        return LogWriter(BufferedWriter(FileWriter(file(itemId), false)), maxBytes).also {
            it.writeRaw(command.replace('\n', ' '))
        }
    }

    fun read(itemId: Long): ItemLog? {
        val f = file(itemId).takeIf { it.isFile } ?: return null
        val text = f.readText()
        val command = text.substringBefore('\n')
        return ItemLog(command, text.substringAfter('\n', "").trimEnd())
    }

    fun delete(itemId: Long) {
        file(itemId).delete()
    }

    companion object {
        const val MAX_BYTES = 256L * 1024
    }
}

/** Appends lines until the size cap, then notes the cut once and ignores the rest. Thread safe. */
class LogWriter internal constructor(private val out: BufferedWriter, private val maxBytes: Long) : AutoCloseable {
    private var written = 0L
    private var truncated = false

    internal fun writeRaw(line: String): Unit = synchronized(this) {
        out.write(line)
        out.newLine()
        written += line.toByteArray().size + 1
    }

    fun append(line: String): Unit = synchronized(this) {
        if (truncated) return
        val size = line.toByteArray().size + 1
        if (written + size > maxBytes) {
            truncated = true
            out.write("… log truncated")
            out.newLine()
            return
        }
        out.write(line)
        out.newLine()
        written += size
    }

    override fun close() = synchronized(this) { out.close() }
}
