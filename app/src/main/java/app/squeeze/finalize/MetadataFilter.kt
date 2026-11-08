package app.squeeze.finalize

import androidx.exifinterface.media.ExifInterface

/**
 * Which EXIF tags survive the post-encode metadata copy. The lists are tag-name strings (the
 * values ExifInterface's `TAG_*` constants hold) so the logic is pure and JVM-testable; the IO
 * is in [MetadataCopier].
 */
object MetadataFilter {

    /** Every GPS tag ExifInterface defines, by name; dropped when the preset keeps no location. */
    private val GPS_TAGS = setOf(
        "GPSAltitude", "GPSAltitudeRef", "GPSAreaInformation", "GPSDOP", "GPSDateStamp",
        "GPSDestBearing", "GPSDestBearingRef", "GPSDestDistance", "GPSDestDistanceRef",
        "GPSDestLatitude", "GPSDestLatitudeRef", "GPSDestLongitude", "GPSDestLongitudeRef",
        "GPSDifferential", "GPSImgDirection", "GPSImgDirectionRef", "GPSLatitude",
        "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef", "GPSMapDatum",
        "GPSProcessingMethod", "GPSSatellites", "GPSSpeed", "GPSSpeedRef", "GPSStatus",
        "GPSTimestamp", "GPSTrack", "GPSTrackRef", "GPSVersionID",
    )

    /** Thumbnail tags would lie about the new pixels and are always dropped. */
    private val THUMBNAIL_TAGS = setOf(
        "ThumbnailEncoding", "ThumbnailImageDescription", "ThumbnailImageLength",
        "ThumbnailImageWidth", "ThumbnailLength", "ThumbnailOffset", "ThumbnailOrientation",
        "ThumbnailResolutionUnit", "ThumbnailXResolution", "ThumbnailYResolution",
    )

    /**
     * @param tags source tag map (ExifInterface tag name → string value).
     * @return the tags to write onto the compressed file: GPS dropped per [keepLocation],
     *   thumbnails gone, orientation reset (the pixels are rotated at encode).
     */
    fun filter(tags: Map<String, String?>, keepExif: Boolean, keepLocation: Boolean): Map<String, String> {
        if (!keepExif) return emptyMap()
        val dropped = THUMBNAIL_TAGS + if (keepLocation) emptySet() else GPS_TAGS
        return tags.filterKeys { it !in dropped }
            .filterValues { !it.isNullOrBlank() }
            .mapValues { it.value ?: "" }
            .plus(ExifInterface.TAG_ORIENTATION to ExifInterface.ORIENTATION_NORMAL.toString())
    }
}
