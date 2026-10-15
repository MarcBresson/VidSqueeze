package app.squeeze.finalize

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android's regex engine (ICU) is stricter than the JVM's: an unescaped `}` compiles on the JVM
 * unit tests but crashed the app. Keeps the template regexes honest on a real runtime.
 */
@RunWith(AndroidJUnit4::class)
class NameTemplateDeviceTest {
    @Test
    fun rendersOnAndroidRuntime() {
        val ctx = NameContext("VID_1.mp4", "mkv", "Share", LocalDate.of(2024, 1, 2), "720p")
        assertEquals("VID_1 - compressed.mkv", NameTemplate.render("{original_file_name} - compressed.{output_format}", ctx))
        assertEquals("Share-2024-01-02-720p.mkv", NameTemplate.render("{preset}-{date}-{resolution}.{output_format}", ctx))
        assertEquals(listOf("nope"), NameTemplate.unknownTokens("{nope}{date}"))
        assertEquals("a (1).mp4", NameTemplate.uniqueName("a.mp4") { it == "a.mp4" })
    }
}
