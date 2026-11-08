package app.squeeze.finalize

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataFilterTest {
    // Tag-name strings; a few tags the filter must handle are not public constants, so the
    // test uses the same names the library uses internally.
    private val tags = mapOf(
        "Make" to "Pixel",
        "Model" to "9",
        "DateTimeOriginal" to "2026:01:01 10:00:00",
        ExifInterface.TAG_ORIENTATION to "6",
        ExifInterface.TAG_GPS_LATITUDE to "51.5",
        ExifInterface.TAG_GPS_LONGITUDE to "0.0",
        ExifInterface.TAG_GPS_ALTITUDE to "10",
        "ThumbnailLength" to "1234",
        "WhiteBalance" to "0",
    )

    @Test
    fun keepExifOffCopiesNothing() {
        assertTrue(MetadataFilter.filter(tags, keepExif = false, keepLocation = true).isEmpty())
    }

    @Test
    fun locationDroppedWhenKeepLocationOff() {
        val out = MetadataFilter.filter(tags, keepExif = true, keepLocation = false)
        assertTrue(out.keys.none { it.contains("GPS") })
        assertEquals("Pixel", out["Make"])
        assertEquals("1", out[ExifInterface.TAG_ORIENTATION]) // always reset: pixels are rotated
    }

    @Test
    fun locationKeptWhenKeepLocationOn() {
        val out = MetadataFilter.filter(tags, keepExif = true, keepLocation = true)
        assertEquals("51.5", out[ExifInterface.TAG_GPS_LATITUDE])
    }

    @Test
    fun thumbnailTagsAlwaysDropped() {
        val out = MetadataFilter.filter(tags, keepExif = true, keepLocation = true)
        assertFalse(out.containsKey("ThumbnailLength"))
    }

    @Test
    fun blankValuesDropped() {
        val out = MetadataFilter.filter(mapOf("Make" to " ", "X" to null), keepExif = true, keepLocation = true)
        assertFalse(out.containsKey("Make"))
        assertFalse(out.containsKey("X"))
    }
}
