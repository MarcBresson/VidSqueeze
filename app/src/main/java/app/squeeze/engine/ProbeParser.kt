package app.squeeze.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Parses `ffprobe -print_format json -show_format -show_streams` output into [SourceInfo].
 * Pure Kotlin so it is unit-testable; [MediaProbe] runs ffprobe on the device.
 */
object ProbeParser {

    /**
     * @param extensionHint file extension of the input, used to tell apart containers that
     * ffprobe reports under one shared demuxer name (mp4 vs mov, mkv vs webm).
     * @return null when the JSON is unusable or holds no video stream.
     */
    fun parse(json: String, extensionHint: String? = null): SourceInfo? {
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        val streams = (root["streams"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val video = streams.firstOrNull {
            it.str("codec_type") == "video" && it["disposition"]?.asObj()?.int("attached_pic") != 1
        } ?: return null
        val audio = streams.firstOrNull { it.str("codec_type") == "audio" }
        val format = root["format"] as? JsonObject

        val sideData = (video["side_data_list"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val rotation = sideData.firstNotNullOfOrNull { it.double("rotation") }
            ?: video["tags"]?.asObj()?.str("rotate")?.toDoubleOrNull()

        val durationSec = format?.double("duration") ?: video.double("duration") ?: 0.0
        val streamBitrate = video.double("bit_rate")
        val videoKbps = streamBitrate?.let { (it / 1000).toInt() }
            ?: format?.double("bit_rate")?.let { total ->
                val audioBps = audio?.double("bit_rate") ?: 0.0
                ((total - audioBps) / 1000).toInt()
            }

        return SourceInfo(
            container = container(format, extensionHint),
            durationMs = (durationSec * 1000).toLong(),
            width = video.int("width") ?: 0,
            height = video.int("height") ?: 0,
            rotation = normaliseRotation(rotation),
            fps = parseRate(video.str("avg_frame_rate")).takeIf { it > 0 } ?: parseRate(video.str("r_frame_rate")),
            videoCodec = video.str("codec_name"),
            pixFmt = video.str("pix_fmt"),
            colorTransfer = video.str("color_transfer")?.takeIf { it != "unknown" },
            colorPrimaries = video.str("color_primaries")?.takeIf { it != "unknown" },
            colorSpace = video.str("color_space")?.takeIf { it != "unknown" },
            dolbyVisionProfile = sideData.firstOrNull {
                it.str("side_data_type")?.startsWith("DOVI", ignoreCase = true) == true
            }?.int("dv_profile"),
            videoBitrateKbps = videoKbps?.takeIf { it > 0 },
            audioCodec = audio?.str("codec_name"),
        )
    }

    private fun container(format: JsonObject?, extensionHint: String?): Container? {
        val names = format?.str("format_name")?.split(',')?.map { it.trim() }.orEmpty()
        val hint = Container.fromExtension(extensionHint)
        return when {
            "matroska" in names -> if (hint == Container.WEBM) Container.WEBM else Container.MKV
            "avi" in names -> Container.AVI
            "mov" in names -> when {
                hint == Container.MOV || hint == Container.MP4 -> hint
                format?.get("tags")?.asObj()?.str("major_brand")?.startsWith("qt") == true -> Container.MOV
                else -> Container.MP4
            }
            else -> hint
        }
    }

    private fun normaliseRotation(rotation: Double?): Int =
        if (rotation == null) 0 else (((rotation.toInt() % 360) + 360) % 360)

    private fun parseRate(rate: String?): Double {
        if (rate == null) return 0.0
        val parts = rate.split('/')
        val num = parts[0].toDoubleOrNull() ?: return 0.0
        val den = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        return if (den == 0.0) 0.0 else num / den
    }

    private fun JsonElement.asObj(): JsonObject? = this as? JsonObject

    // ffprobe emits numbers as strings in some fields and as numbers in others; accept both.
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
}
