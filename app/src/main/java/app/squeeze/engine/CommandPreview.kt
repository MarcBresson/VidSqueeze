package app.squeeze.engine

/** What the compression sheet shows under the settings: the exact command, notices and errors. */
data class CommandPreview(
    val command: String?,
    val container: Container?,
    val issues: List<Issue>,
) {
    val hasErrors: Boolean get() = issues.hasErrors

    companion object {
        /** A typical phone clip, used until the first real file has been probed. */
        val SAMPLE_SOURCE = SourceInfo(
            container = Container.MP4, durationMs = 60_000, width = 1920, height = 1080, fps = 30.0,
            videoCodec = "h264", pixFmt = "yuv420p", videoBitrateKbps = 16_000, audioCodec = "aac",
        )

        const val INPUT_PLACEHOLDER = "<input>"

        fun of(preset: Preset, source: SourceInfo?, options: BuildOptions = BuildOptions()): CommandPreview {
            val src = source ?: SAMPLE_SOURCE
            val resolution = CodecContainerMatrix.resolveContainer(preset.params, src)
            val container = resolution.container ?: return CommandPreview(null, null, resolution.issues)
            val build = FfmpegCommandBuilder.build(
                preset.params, src, INPUT_PLACEHOLDER, "<output>.${container.extension}", preset.export, options,
            )
            return when (build) {
                is BuildResult.Ok -> CommandPreview(build.display(), build.container, build.issues)
                is BuildResult.Invalid -> CommandPreview(null, container, build.issues)
            }
        }
    }
}
