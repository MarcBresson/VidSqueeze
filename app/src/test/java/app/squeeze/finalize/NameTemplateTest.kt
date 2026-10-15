package app.squeeze.finalize

import app.squeeze.engine.ExportOptions
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameTemplateTest {
    private val ctx = NameContext("IMG_0001.MOV", "mp4", "Share", LocalDate.of(2026, 10, 14), "720p")

    @Test
    fun defaultTemplate() {
        assertEquals("IMG_0001 - compressed.mp4", NameTemplate.render(ExportOptions.DEFAULT_NAME_TEMPLATE, ctx))
    }

    @Test
    fun allTokens() {
        assertEquals(
            "IMG_0001_Share_2026-10-14_720p.mp4",
            NameTemplate.render("{original_file_name}_{preset}_{date}_{resolution}.{output_format}", ctx),
        )
    }

    @Test
    fun extensionAppendedWhenMissing() {
        assertEquals("clip.mp4", NameTemplate.render("clip", ctx))
        assertEquals("clip.MP4", NameTemplate.render("clip.MP4", ctx))
    }

    @Test
    fun illegalCharactersReplaced() {
        val c = ctx.copy(presetName = "a/b:c*d")
        assertEquals("IMG_0001 a_b_c_d.mp4", NameTemplate.render("{original_file_name} {preset}", c))
    }

    @Test
    fun emptyAndDotNamesFallBack() {
        assertEquals("video.mp4", NameTemplate.render("", ctx))
        assertEquals("hidden.mp4", NameTemplate.render("..hidden", ctx))
    }

    @Test
    fun unknownTokensReportedAndKept() {
        assertEquals(listOf("nope"), NameTemplate.unknownTokens("{nope}-{date}-{nope}"))
        assertTrue(NameTemplate.render("{nope}", ctx).startsWith("{nope}"))
    }

    @Test
    fun collisionsGetCounter() {
        val taken = setOf("a.mp4", "a (1).mp4")
        assertEquals("a (2).mp4", NameTemplate.uniqueName("a.mp4") { it in taken })
        assertEquals("b.mp4", NameTemplate.uniqueName("b.mp4") { it in taken })
    }

    @Test
    fun longNamesTruncatedKeepingExtension() {
        val name = NameTemplate.render("x".repeat(400), ctx)
        assertTrue(name.toByteArray().size <= 255)
        assertTrue(name.endsWith(".mp4"))
    }
}
