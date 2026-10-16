package app.squeeze.engine

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real FFmpegKit on a device: generates a clip, reads it through a content URI, compresses it. */
@RunWith(AndroidJUnit4::class)
class FfmpegKitEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = FfmpegKitEngine(context)
    private lateinit var dir: File
    private val inserted = mutableListOf<Uri>()

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "engine-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        inserted.forEach { context.contentResolver.delete(it, null, null) }
        dir.deleteRecursively()
    }

    private fun generateClip(name: String, seconds: Int = 3, size: String = "1920x1080", rate: Int = 60): File {
        val out = File(dir, name)
        val args = listOf(
            "-y", "-f", "lavfi", "-i", "testsrc2=size=$size:rate=$rate:duration=$seconds",
            "-f", "lavfi", "-i", "sine=duration=$seconds",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", out.absolutePath,
        )
        val result = runBlocking { engine.run(args, seconds * 1000L) }
        assertEquals(RunResult.Success, result)
        return out
    }

    private fun toContentUri(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "squeeze-test-${System.nanoTime()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SqueezeTest")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        inserted += uri
        return uri
    }

    @Test
    fun probeReadsGeneratedClipThroughContentUri() = runBlocking {
        val uri = toContentUri(generateClip("probe.mp4"))
        val info = engine.probe(engine.inputFor(uri), "mp4")
        assertNotNull(info)
        info!!
        assertEquals(1920 to 1080, info.width to info.height)
        assertEquals("h264", info.videoCodec)
        assertEquals("aac", info.audioCodec)
        assertEquals(60.0, info.fps, 0.5)
        assertEquals(Container.MP4, info.container)
        assertTrue("duration ${info.durationMs}", info.durationMs in 2_500..3_500)
    }

    @Test
    fun compressesContentUriWithShareLikePreset() = runBlocking {
        val clip = generateClip("in.mp4")
        val uri = toContentUri(clip)
        val source = engine.probe(engine.inputFor(uri), "mp4")!!
        val params = FfmpegParams(
            videoCodec = VideoCodec.H264, rateControl = RateControl.Crf(30), encoderPreset = EncoderPreset.VERYFAST,
            maxShortSide = 720, fpsMax = 30, container = Container.MP4,
        )
        val output = File(dir, "out.mp4")
        val build = FfmpegCommandBuilder.build(params, source, engine.inputFor(uri), output.absolutePath)
        build as BuildResult.Ok

        val progress = mutableListOf<EncodeProgress>()
        val result = engine.run(build.args, source.durationMs) { progress += it }

        assertEquals(RunResult.Success, result)
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.all { it.fraction == null || it.fraction in 0f..1f })
        assertTrue("output ${output.length()} vs input ${clip.length()}", output.length() in 1 until clip.length())

        val out = engine.probe(output.absolutePath, "mp4")!!
        assertEquals(720, minOf(out.width, out.height))
        assertEquals(1280, maxOf(out.width, out.height))
        assertEquals(30.0, out.fps, 0.5)
        assertEquals("h264", out.videoCodec)
    }

    @Test
    fun h265Mp4IsTaggedHvc1() = runBlocking {
        val clip = generateClip("in265.mp4", seconds = 2, size = "1280x720", rate = 30)
        val source = engine.probe(clip.absolutePath, "mp4")!!
        val output = File(dir, "out265.mp4")
        val build = FfmpegCommandBuilder.build(
            FfmpegParams(encoderPreset = EncoderPreset.ULTRAFAST, rateControl = RateControl.Crf(32)),
            source, clip.absolutePath, output.absolutePath,
        ) as BuildResult.Ok
        assertEquals(RunResult.Success, engine.run(build.args, source.durationMs))
        assertEquals("hevc", engine.probe(output.absolutePath, "mp4")!!.videoCodec)
    }

    @Test
    fun failureCarriesLogTail() = runBlocking {
        val result = engine.run(listOf("-y", "-i", File(dir, "missing.mp4").absolutePath, File(dir, "x.mp4").absolutePath), 0)
        assertTrue(result is RunResult.Failed)
        assertTrue((result as RunResult.Failed).logTail.isNotBlank())
    }

    @Test
    fun cancellingTheCoroutineStopsFfmpeg() = runBlocking {
        val clip = generateClip("long.mp4", seconds = 20, size = "1920x1080", rate = 30)
        val output = File(dir, "cancelled.mkv")
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            engine.run(
                listOf("-y", "-i", clip.absolutePath, "-c:v", "libx265", "-preset", "veryslow", "-crf", "20", output.absolutePath),
                20_000,
            )
        }
        delay(2_000)
        job.cancel()
        val started = System.currentTimeMillis()
        try { job.await() } catch (_: kotlinx.coroutines.CancellationException) {}
        assertTrue("cancel took too long", System.currentTimeMillis() - started < 10_000)
        // A following session must still work after a cancel.
        assertEquals(RunResult.Success, engine.run(listOf("-y", "-i", clip.absolutePath, "-t", "1", "-c", "copy", File(dir, "after.mp4").absolutePath), 1000))
    }

    @Test
    fun toneMapChainIsAvailableInTheBuild() = runBlocking {
        val args = listOf(
            "-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=10:duration=1",
            "-vf", "format=yuv420p10le,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc,${FfmpegCommandBuilder.TONE_MAP_CHAIN},format=yuv420p",
            "-f", "null", "-",
        )
        val result = engine.run(args, 1000)
        assertEquals(result.toString(), RunResult.Success, result)
    }

    /** The hardware path leans on these options (see FfmpegCommandBuilder), so the bundled build must have them. `-global_quality` is a generic codec option, so `-h encoder` does not list it. */
    @Test
    fun mediaCodecEncodersHaveTheOptionsWeUse() = runBlocking {
        for (encoder in listOf("h264_mediacodec", "hevc_mediacodec")) {
            val help = StringBuffer()
            val result = engine.run(listOf("-hide_banner", "-h", "encoder=$encoder"), 1000, onLog = { help.appendLine(it) })
            delay(500) // the log callback can lag behind completion
            assertEquals(result.toString(), RunResult.Success, result)
            for (option in listOf("-bitrate_mode", "-codec_name", "-qp_i_min")) {
                assertTrue("$encoder lacks $option:\n$help", help.contains(option))
            }
        }
    }

    @Test
    fun hardwareEncoderProbeDoesNotThrow() {
        val found = HardwareEncoderProbe().encoders
        found.byCodec.values.forEach { assertTrue(it.name, it.name.isNotBlank()) }
    }
}
