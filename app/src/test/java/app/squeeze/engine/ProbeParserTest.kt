package app.squeeze.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeParserTest {
    private val pixelHdr = """
        {"streams":[
          {"index":0,"codec_type":"video","codec_name":"hevc","width":3840,"height":2160,"pix_fmt":"yuv420p10le",
           "color_space":"bt2020nc","color_transfer":"smpte2084","color_primaries":"bt2020",
           "avg_frame_rate":"30000/1001","r_frame_rate":"30/1","bit_rate":"45000000","duration":"10.5",
           "side_data_list":[{"side_data_type":"Display Matrix","rotation":-90},
                             {"side_data_type":"DOVI configuration record","dv_profile":8}]},
          {"index":1,"codec_type":"audio","codec_name":"aac","bit_rate":"192000"}],
         "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"10.533000","bit_rate":"45500000","tags":{"major_brand":"isom"}}}
    """.trimIndent()

    @Test
    fun parsesHdrPhoneClip() {
        val s = ProbeParser.parse(pixelHdr, "mp4")!!
        assertEquals(Container.MP4, s.container)
        assertEquals(10_533, s.durationMs)
        assertEquals(3840 to 2160, s.width to s.height)
        assertEquals(270, s.rotation)
        assertEquals(2160, s.displayWidth)
        assertEquals(29.97, s.fps, 0.01)
        assertEquals("hevc", s.videoCodec)
        assertEquals("yuv420p10le", s.pixFmt)
        assertTrue(s.isHdr)
        assertEquals(8, s.dolbyVisionProfile)
        assertEquals(45_000, s.videoBitrateKbps)
        assertEquals("aac", s.audioCodec)
        assertTrue(s.hasAudio)
    }

    @Test
    fun legacyRotateTagAndUnknownColourTags() {
        val json = """{"streams":[{"codec_type":"video","codec_name":"h264","width":1920,"height":1080,
            "color_transfer":"unknown","avg_frame_rate":"0/0","r_frame_rate":"25/1","tags":{"rotate":"90"}}],
            "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"3","bit_rate":"8000000","tags":{"major_brand":"qt  "}}}"""
        val s = ProbeParser.parse(json)!!
        assertEquals(90, s.rotation)
        assertNull(s.colorTransfer)
        assertFalse(s.isHdr)
        assertEquals(25.0, s.fps, 0.001)
        assertEquals(Container.MOV, s.container)
        assertEquals(8000, s.videoBitrateKbps) // no audio stream: whole container bitrate
        assertFalse(s.hasAudio)
    }

    @Test
    fun matroskaVsWebmUsesHint() {
        val json = """{"streams":[{"codec_type":"video","codec_name":"vp9","width":640,"height":360}],"format":{"format_name":"matroska,webm","duration":"1"}}"""
        assertEquals(Container.WEBM, ProbeParser.parse(json, "webm")!!.container)
        assertEquals(Container.MKV, ProbeParser.parse(json, "mkv")!!.container)
        assertEquals(Container.MKV, ProbeParser.parse(json, null)!!.container)
    }

    @Test
    fun coverArtIsNotTheVideoStream() {
        val json = """{"streams":[
            {"codec_type":"video","codec_name":"mjpeg","width":300,"height":300,"disposition":{"attached_pic":1}},
            {"codec_type":"video","codec_name":"h264","width":1280,"height":720}],"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2"}}"""
        assertEquals("h264", ProbeParser.parse(json)!!.videoCodec)
    }

    @Test
    fun unusableInputReturnsNull() {
        assertNull(ProbeParser.parse("not json"))
        assertNull(ProbeParser.parse("""{"streams":[{"codec_type":"audio"}]}"""))
        assertNull(ProbeParser.parse("{}"))
    }

    @Test
    fun progressGuardsZeroDuration() {
        assertNull(Progress.fraction(1000, 0))
        assertEquals(0.5f, Progress.fraction(5_000, 10_000)!!, 0.0001f)
        assertEquals(1f, Progress.fraction(20_000, 10_000)!!, 0.0001f)
        assertEquals(10_000L, Progress.etaMs(0, 10_000, 1.0))
        assertEquals(5_000L, Progress.etaMs(5_000, 20_000, 3.0))
        assertNull(Progress.etaMs(0, 10_000, 0.0))
    }
}
