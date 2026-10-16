package app.squeeze.work

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(max: Long = 1024) = LogStore(File(tmp.root, "logs"), max)

    @Test
    fun `command is the first line and the output follows`() {
        val store = store()
        store.open(7, "ffmpeg -i in.mp4 out.mp4").use {
            it.append("frame one")
            it.append("frame two")
        }
        assertTrue(store.exists(7))
        assertEquals(ItemLog("ffmpeg -i in.mp4 out.mp4", "frame one\nframe two"), store.read(7))
    }

    @Test
    fun `output is cut at the cap with a note`() {
        val store = store(max = 64)
        store.open(1, "cmd").use { writer -> repeat(50) { writer.append("0123456789") } }
        val log = store.read(1)!!
        assertTrue(log.output.endsWith("… log truncated"))
        assertTrue(File(tmp.root, "logs/1.log").length() < 128)
    }

    @Test
    fun `opening again starts a fresh log and delete removes it`() {
        val store = store()
        store.open(3, "first").use { it.append("old") }
        store.open(3, "second").use { it.append("new") }
        assertEquals(ItemLog("second", "new"), store.read(3))
        store.delete(3)
        assertFalse(store.exists(3))
        assertNull(store.read(3))
    }
}
