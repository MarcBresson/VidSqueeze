package app.squeeze.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutActionTest {
    @Test
    fun `run is offered only while auto compression is on`() {
        assertEquals(listOf(ShortcutAction.RUN, ShortcutAction.PAUSE, ShortcutAction.RESUME), ShortcutAction.available(true))
        assertEquals(listOf(ShortcutAction.PAUSE, ShortcutAction.RESUME), ShortcutAction.available(false))
    }

    @Test
    fun `ids round trip and unknown ids are refused`() {
        ShortcutAction.entries.forEach { assertEquals(it, ShortcutAction.fromId(it.id)) }
        assertNull(ShortcutAction.fromId("cancel_all"))
        assertNull(ShortcutAction.fromId(null))
    }
}
