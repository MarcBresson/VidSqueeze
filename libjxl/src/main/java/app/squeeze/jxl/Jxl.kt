package app.squeeze.jxl

/**
 * JPEG XL encoding through the bundled libjxl (`:libjxl` native module). The only entry point
 * the app uses; decode stays with FFmpeg's native decoder for now.
 */
object Jxl {

    init {
        System.loadLibrary("jxl_jni")
    }

    /**
     * Encodes tightly packed row-major pixels into a `.jxl` codestream.
     *
     * @param pixels width*height*[channels] samples; 8-bit → UINT8, 16-bit → UINT16,
     *   32 → FLOAT16 (libjxl's float pixel type).
     * @param effort 1..9 (libjxl default 7).
     * @param distance 0.0 = lossless, 1.0 ≈ visually lossless, up to 7.0.
     * @param modular modular mode (UI shots / lossless) instead of VarDCT (photos).
     * @param xyb force the XYB colourspace for lossless encodes; keep false for bit-exact round-trips.
     * @param exif raw EXIF bytes (TIFF header included) written as the codestream's Exif box, or null.
     * @return the codestream bytes, or null when libjxl refused the request.
     */
    external fun encodeJxl(
        width: Int,
        height: Int,
        hasAlpha: Boolean,
        bitDepth: Int,
        effort: Int,
        distance: Double,
        modular: Boolean,
        xyb: Boolean,
        pixels: ByteArray,
        exif: ByteArray?,
    ): ByteArray?

    /** Convenience wrapper for the common 8-bit path; returns null on failure. */
    fun encode(
        width: Int,
        height: Int,
        hasAlpha: Boolean,
        pixels: ByteArray,
        params: JxlSettings,
        exif: ByteArray? = null,
    ): ByteArray? = encodeJxl(width, height, hasAlpha, params.bitDepth, params.effort, params.distance, params.modular, params.xyb, pixels, exif)
}

/** Kotlin-side mirror of the JNI parameters; pure data so presets can hold it. */
data class JxlSettings(
    val effort: Int = 7,
    val distance: Double = 1.0,
    val modular: Boolean = false,
    val xyb: Boolean = false,
    val bitDepth: Int = 8,
)
