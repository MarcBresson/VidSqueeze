package app.squeeze.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCodecsTest {
    private fun entry(name: String, mime: String, hardware: Boolean = true, kind: CodecKind = CodecKind.VIDEO) = EncoderEntry(
        name = name,
        mime = mime,
        kind = kind,
        isHardware = hardware,
        supportsCq = false,
        qualityRange = null,
        bitrateRangeKbps = null,
        video = null,
        audio = null,
    )

    private fun audio(name: String, mime: String, hardware: Boolean = false) = entry(name, mime, hardware, CodecKind.AUDIO)

    @Test
    fun `encoders of one mime type end up in one group with a readable label`() {
        val groups = groupEncoders(listOf(entry("a", "video/avc"), entry("b", "video/avc", hardware = false)))
        assertEquals(1, groups.size)
        assertEquals("H.264", groups.single().label)
        assertEquals(2, groups.single().encoders.size)
    }

    @Test
    fun `an unknown mime type is labelled with itself`() {
        assertEquals("video/x-odd", groupEncoders(listOf(entry("a", "video/x-odd"))).single().label)
    }

    @Test
    fun `hardware encoders come first inside a group`() {
        val group = groupEncoders(
            listOf(
                entry("c2.android.av1.encoder", "video/av01", hardware = false),
                entry("c2.google.av1.encoder", "video/av01"),
            ),
        ).single()
        assertEquals(listOf("c2.google.av1.encoder", "c2.android.av1.encoder"), group.encoders.map { it.name })
        assertTrue(group.hasHardware)
    }

    @Test
    fun `a group of software encoders has no hardware`() {
        assertFalse(groupEncoders(listOf(entry("sw", "video/av01", hardware = false))).single().hasHardware)
    }

    @Test
    fun `video comes before audio and known codecs before unknown ones`() {
        val groups = groupEncoders(
            listOf(
                audio("opus", "audio/opus"),
                entry("odd", "video/x-odd"),
                audio("aac", "audio/mp4a-latm"),
                entry("av1", "video/av01"),
                entry("avc", "video/avc"),
            ),
        )
        assertEquals(listOf("H.264", "AV1", "video/x-odd", "AAC", "Opus"), groups.map { it.label })
    }
}
