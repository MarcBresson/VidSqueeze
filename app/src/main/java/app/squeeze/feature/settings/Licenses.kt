package app.squeeze.feature.settings

/** Where the project's source is published; null hides the link until a public repository exists. */
val SOURCE_URL: String? = null

/** A bundled licence text (`res/raw/<rawName>.txt`), shown by name in About. */
data class LicenseEntry(val title: String, val rawName: String)

/** A project whose licence is a well-known public text, linked instead of bundled. */
data class LicenseLink(val title: String, val licence: String, val url: String)

object Licenses {
    const val APP = "squeeze_license"
    const val SOURCE_OFFER = "source"

    /** The notices that ship inside the FFmpegKit library; the About screen is how we pass them on. */
    val ffmpeg: List<LicenseEntry> = listOf(
        LicenseEntry("FFmpegKit and FFmpeg", "license"),
        LicenseEntry("x264", "license_x264"),
        LicenseEntry("x265", "license_x265"),
        LicenseEntry("libvpx", "license_libvpx"),
        LicenseEntry("libaom", "license_libaom"),
        LicenseEntry("dav1d", "license_dav1d"),
        LicenseEntry("Opus", "license_opus"),
        LicenseEntry("LAME", "license_lame"),
        LicenseEntry("libvorbis", "license_libvorbis"),
        LicenseEntry("libogg", "license_libogg"),
        LicenseEntry("libtheora", "license_libtheora"),
        LicenseEntry("Speex", "license_speex"),
        LicenseEntry("libass", "license_libass"),
        LicenseEntry("FreeType", "license_freetype"),
        LicenseEntry("HarfBuzz", "license_harfbuzz"),
        LicenseEntry("FriBidi", "license_fribidi"),
        LicenseEntry("Fontconfig", "license_fontconfig"),
        LicenseEntry("libpng", "license_libpng"),
        LicenseEntry("libxml2", "license_libxml2"),
        LicenseEntry("Expat", "license_expat"),
        LicenseEntry("libiconv", "license_libiconv"),
        LicenseEntry("zimg", "license_zimg"),
        LicenseEntry("libvidstab", "license_libvidstab"),
        LicenseEntry("Kvazaar", "license_kvazaar"),
        LicenseEntry("OpenH264", "license_openh264"),
        LicenseEntry("Xvid", "license_xvidcore"),
        LicenseEntry("Rubber Band", "license_rubberband"),
        LicenseEntry("libsamplerate", "license_libsamplerate"),
        LicenseEntry("libsndfile", "license_libsndfile"),
        LicenseEntry("SoX Resampler", "license_soxr"),
        LicenseEntry("TwoLAME", "license_twolame"),
        LicenseEntry("Shine", "license_shine"),
        LicenseEntry("OpenCORE AMR", "license_opencore_amr"),
        LicenseEntry("VisualOn AMR-WB encoder", "license_vo_amrwbenc"),
        LicenseEntry("iLBC", "license_libilbc"),
        LicenseEntry("Snappy", "license_snappy"),
        LicenseEntry("SDL", "license_sdl"),
        LicenseEntry("cpu_features", "license_cpu_features"),
        LicenseEntry("libuuid", "license_libuuid"),
    )

    val android: List<LicenseLink> = listOf(
        LicenseLink("AndroidX, Jetpack Compose, Room, WorkManager, DataStore, Navigation", "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
        LicenseLink("Kotlin and kotlinx libraries", "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
        LicenseLink("Dagger and Hilt", "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    )
}
