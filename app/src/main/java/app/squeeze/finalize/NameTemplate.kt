package app.squeeze.finalize

import java.time.LocalDate

/** Values available to the template tokens. [originalFileName] may include its extension. */
data class NameContext(
    val originalFileName: String,
    val outputFormat: String,
    val presetName: String,
    val date: LocalDate,
    /** e.g. "1080p", or "original". */
    val resolution: String,
)

/** Expands `{tokens}` in the user's output name template into a safe file name. */
object NameTemplate {
    val TOKENS = listOf("original_file_name", "output_format", "preset", "date", "resolution")

    private val tokenRegex = Regex("\\{([a-zA-Z_]+)\\}")
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private const val MAX_NAME_BYTES = 255

    /** Token names used by [template] that are not supported. */
    fun unknownTokens(template: String): List<String> =
        tokenRegex.findAll(template).map { it.groupValues[1] }.filter { it !in TOKENS }.distinct().toList()

    fun render(template: String, ctx: NameContext): String {
        val ext = ctx.outputFormat.trimStart('.')
        val stem = ctx.originalFileName.substringBeforeLast('.', ctx.originalFileName)
        val expanded = tokenRegex.replace(template) { m ->
            when (m.groupValues[1]) {
                "original_file_name" -> sanitize(stem)
                "output_format" -> ext
                "preset" -> sanitize(ctx.presetName)
                "date" -> ctx.date.toString()
                "resolution" -> sanitize(ctx.resolution)
                else -> m.value
            }
        }
        var name = sanitize(expanded).trim().trimStart('.')
        if (name.isEmpty()) name = "video"
        if (!name.endsWith(".$ext", ignoreCase = true)) name = "$name.$ext"
        return truncate(name)
    }

    /** Returns [name], or "name (1).ext", "name (2).ext", ... until [exists] says the name is free. */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (true) {
            val candidate = truncate("$stem ($n)$ext")
            if (!exists(candidate)) return candidate
            n++
        }
    }

    private fun sanitize(s: String): String = illegal.replace(s, "_")

    private fun truncate(name: String): String {
        if (name.toByteArray().size <= MAX_NAME_BYTES) return name
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var stem = name.removeSuffix(ext)
        while ((stem + ext).toByteArray().size > MAX_NAME_BYTES) stem = stem.dropLast(1)
        return stem + ext
    }
}
