package app.squeeze.finalize

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the source photo's EXIF before the encode (orientation to bake into the pixels, tag map
 * for the copy) and writes the filtered tags onto the compressed JPEG/WebP after it. FFmpeg has
 * no EXIF writer for still images, so this step is the only thing preserving photo metadata.
 */
@Singleton
class MetadataCopier @Inject constructor(@ApplicationContext private val context: Context) {

    data class SourceExif(val orientationDegrees: Int, val tags: Map<String, String?>)

    /** @return null when the source carries no EXIF (PNG/AVIF/JXL in) or cannot be read. */
    fun readSource(uri: Uri): SourceExif? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val tags = allTags(exif)
            if (tags.isEmpty()) null else SourceExif(exif.rotationDegrees, tags)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Applies [MetadataFilter.filter] onto the compressed file. Formats without an EXIF writer
     * (PNG/AVIF via FFmpeg) return false; the caller logs it instead of failing the item.
     * JPEG XL embeds metadata only as a raw TIFF block, which ExifInterface cannot hand out, so
     * photo metadata on JXL output is a documented gap for now.
     */
    fun copyTo(output: File, sourceTags: Map<String, String?>, keepExif: Boolean, keepLocation: Boolean): Boolean {
        if (!keepExif) return true
        val filtered = MetadataFilter.filter(sourceTags, keepExif, keepLocation)
        return try {
            val out = ExifInterface(output.absolutePath)
            filtered.forEach { (tag, value) -> out.setAttribute(tag, value) }
            out.saveAttributes()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Every tag the source actually carries (ExifInterface has no public enumeration API). */
    private fun allTags(exif: ExifInterface): Map<String, String?> =
        METADATA_TAG_NAMES.associateWith { exif.getAttribute(it) }

    private companion object {
        /** Every `TAG_*` String constant of ExifInterface, via reflection. */
        val METADATA_TAG_NAMES: List<String> = ExifInterface::class.java.fields
            .filter { it.name.startsWith("TAG_") && it.type == String::class.java }
            .map { it.get(null) as String }
    }
}

/** EXIF rotation (ExifInterface.rotationDegrees) to bake into the pixels before encoding. */
fun exifDegrees(rotationDegrees: Int): Int = ((rotationDegrees % 360) + 360) % 360
