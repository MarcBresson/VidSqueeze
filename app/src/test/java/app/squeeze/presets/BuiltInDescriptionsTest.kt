package app.squeeze.presets

import app.squeeze.engine.BuiltInPresets
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInDescriptionsTest {
    @Test
    fun `every built-in preset has a description`() {
        for (p in BuiltInPresets.all) {
            val d = BuiltInDescriptions.forId(p.id)
            assertTrue("no description for ${p.id}", d.isNotBlank())
        }
    }
}
