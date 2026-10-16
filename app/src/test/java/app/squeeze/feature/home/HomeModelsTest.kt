package app.squeeze.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeModelsTest {
    @Test
    fun `resolution is named after the long side`() {
        assertEquals("8K", ResolutionLabel.of(7680, 4320))
        assertEquals("4K", ResolutionLabel.of(3840, 2160))
        assertEquals("4K", ResolutionLabel.of(2160, 3840))
        assertEquals("2K", ResolutionLabel.of(2560, 1440))
        assertEquals("1080p", ResolutionLabel.of(1920, 1080))
        assertEquals("720p", ResolutionLabel.of(1280, 720))
        assertEquals("SD", ResolutionLabel.of(640, 480))
    }

    @Test
    fun `unknown resolution has no label`() {
        assertNull(ResolutionLabel.of(0, 0))
    }

    @Test
    fun `reduction is the saved share in whole percent`() {
        assertEquals(60, Reduction.percent(1000, 400))
        assertEquals(0, Reduction.percent(1000, 1500))
    }

    @Test
    fun `reduction is unknown without a result or a size`() {
        assertNull(Reduction.percent(1000, null))
        assertNull(Reduction.percent(0, 0))
    }

    @Test
    fun `promo reclaimed share follows the reduction`() {
        assertEquals(0.75f, AutoPromo(2, 4000, 1000).reclaimedFraction, 0.001f)
        assertEquals(0f, AutoPromo(2, 4000, null).reclaimedFraction, 0.001f)
    }
}
