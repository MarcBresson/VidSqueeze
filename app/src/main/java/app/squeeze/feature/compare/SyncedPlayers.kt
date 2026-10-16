package app.squeeze.feature.compare

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import app.squeeze.ui.media.VideoRef

/**
 * Two ExoPlayers that play the original and the compressed video as one: shared play/pause and seek,
 * one audible at a time, and the follower re-aligned to the leader whenever [tick] sees them drift.
 * The leader is the original unless it failed, then the compressed one takes over.
 *
 * Observable state is Compose state, so the UI reads it directly; call [release] when done.
 */
@androidx.annotation.OptIn(UnstableApi::class) // DefaultRenderersFactory decoder fallback
class SyncedPlayers(context: Context, original: VideoRef, compressed: VideoRef, startMs: Long) {
    private val factory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)

    private val players: Map<CompareSide, ExoPlayer> = mapOf(
        CompareSide.ORIGINAL to build(context, original, startMs),
        CompareSide.COMPRESSED to build(context, compressed, startMs),
    )

    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(startMs)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var ended by mutableStateOf(false)
        private set

    /** Sides whose video could not be played (codec not supported, file gone...). */
    var failed by mutableStateOf(emptySet<CompareSide>())
        private set

    /** Sides that put a frame on their surface since the last [refreshFrame]. */
    private var drawn = emptySet<CompareSide>()

    var audioFrom by mutableStateOf(CompareSide.COMPRESSED)
        private set

    init {
        applyVolumes()
        players.forEach { (side, player) -> player.addListener(listenerFor(side)) }
    }

    fun player(side: CompareSide): ExoPlayer = players.getValue(side)

    private val leaderSide get() = if (CompareSide.ORIGINAL in failed) CompareSide.COMPRESSED else CompareSide.ORIGINAL
    private val leader get() = player(leaderSide)
    private val followers get() = players.filterKeys { it != leaderSide && it !in failed }.values

    private fun build(context: Context, video: VideoRef, startMs: Long): ExoPlayer =
        ExoPlayer.Builder(context, factory).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(MediaItem.fromUri(video.uri), startMs)
            prepare()
        }

    private fun listenerFor(side: CompareSide) = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            failed = failed + side
            applyVolumes()
            syncPlayState()
        }

        override fun onRenderedFirstFrame() {
            drawn += side
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (side != leaderSide) return
            if (state == Player.STATE_ENDED) {
                ended = true
                pause()
            }
            if (state == Player.STATE_READY) durationMs = leader.duration.coerceAtLeast(0)
        }
    }

    fun play() {
        if (ended) seekTo(0)
        ended = false
        isPlaying = true
        syncPlayState()
    }

    fun pause() {
        isPlaying = false
        syncPlayState()
    }

    fun toggle() = if (isPlaying) pause() else play()

    private fun syncPlayState() {
        players.forEach { (side, player) -> if (side !in failed) player.playWhenReady = isPlaying }
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0)
        ended = false
        positionMs = target
        players.forEach { (side, player) -> if (side !in failed) player.seekTo(target) }
    }

    /**
     * Draws the current frame. A paused player shows nothing on a surface that was just attached
     * (first display, layout change), so let it run for a blink, silently, and put it back.
     */
    suspend fun refreshFrame() {
        if (isPlaying || ended) return
        val live = players.filterKeys { it !in failed }.values
        val at = if (durationMs > 0) positionMs.coerceAtMost(durationMs - 1) else positionMs
        drawn = emptySet()
        live.forEach { it.volume = 0f; it.playWhenReady = true }
        // Software decoders can be slow: wait until every pane drew, but not forever.
        var waited = 0L
        while (drawn.size < live.size && waited < 1_000) {
            delay(25)
            waited += 25
        }
        if (!isPlaying) live.forEach { it.playWhenReady = false; it.seekTo(at) }
        applyVolumes()
    }

    /** Which video's sound you hear; the other is muted. */
    fun selectAudio(side: CompareSide) {
        audioFrom = side
        applyVolumes()
    }

    private fun applyVolumes() {
        // A failed side cannot be heard, so fall back to the one that still plays.
        val heard = if (audioFrom in failed) audioFrom.other else audioFrom
        players.forEach { (side, player) -> player.volume = if (side == heard) 1f else 0f }
    }

    /** Call a few times a second: publishes the position and pulls a drifting follower back in line. */
    fun tick() {
        if (failed.size == 2) return
        if (leader.duration > 0) durationMs = leader.duration
        positionMs = leader.currentPosition
        if (!isPlaying) return
        followers.forEach { follower ->
            if (SyncPolicy.needsResync(positionMs, follower.currentPosition)) follower.seekTo(positionMs)
        }
    }

    fun release() = players.values.forEach(ExoPlayer::release)
}
