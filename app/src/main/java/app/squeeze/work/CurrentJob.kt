package app.squeeze.work

import app.squeeze.engine.FfmpegKitEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the worker is encoding right now, so "cancel current" can stop it even before ffmpeg has
 * started (while the file is still being probed).
 */
@Singleton
class CurrentJob @Inject constructor(private val engine: FfmpegKitEngine) {
    @Volatile var itemId: Long? = null
        private set

    @Volatile private var cancelRequested = false

    fun begin(id: Long) {
        cancelRequested = false
        itemId = id
    }

    fun end() {
        itemId = null
        cancelRequested = false
    }

    val isCancelRequested get() = cancelRequested

    fun requestCancel() {
        if (itemId == null) return
        cancelRequested = true
        engine.cancelRunning()
    }
}
