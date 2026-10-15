package app.squeeze.engine

class ArgSyntaxException(message: String) : IllegalArgumentException(message)

/** Shell-like tokeniser and sanity checks for the user's custom ffmpeg arguments. */
object CustomArgs {

    /** Splits on whitespace; supports 'single', "double" quotes and backslash escapes outside single quotes. */
    fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inToken = false
        var quote: Char? = null
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                quote == '\'' -> if (ch == '\'') quote = null else current.append(ch)
                quote == '"' -> when {
                    ch == '"' -> quote = null
                    ch == '\\' && i + 1 < input.length && input[i + 1] in "\"\\" -> current.append(input[++i])
                    else -> current.append(ch)
                }
                ch == '\'' || ch == '"' -> { quote = ch; inToken = true }
                ch == '\\' && i + 1 < input.length -> { current.append(input[++i]); inToken = true }
                ch.isWhitespace() -> if (inToken) {
                    tokens += current.toString(); current.clear(); inToken = false
                }
                else -> { current.append(ch); inToken = true }
            }
            i++
        }
        if (quote != null) throw ArgSyntaxException("Unterminated $quote quote in custom arguments.")
        if (inToken) tokens += current.toString()
        return tokens
    }

    private val forbiddenFlags = setOf("-i", "-y", "-n")
    private val mediaExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "mp3", "aac", "m4a", "opus", "ogg", "wav", "flac", "gif", "png", "jpg", "ts",
    )

    /**
     * The app owns the input, the output and the overwrite policy. Output paths can't be detected
     * with certainty, so this flags tokens that look like a media file path.
     */
    fun validate(tokens: List<String>): List<Issue> = buildList {
        for (token in tokens) {
            if (token in forbiddenFlags) {
                add(Issue(Severity.ERROR, "custom_forbidden_flag", "Custom arguments cannot contain $token (the app sets input, output and overwrite)."))
            } else if (looksLikeOutputPath(token)) {
                add(Issue(Severity.ERROR, "custom_output_path", "Custom arguments cannot contain an output path ($token)."))
            }
        }
    }

    private fun looksLikeOutputPath(token: String): Boolean {
        if (token.startsWith("-") || '=' in token) return false
        val ext = token.substringAfterLast('.', "").lowercase()
        return ext in mediaExtensions && token.length > ext.length + 1
    }
}

/** Renders an argument list as a copy-pasteable shell command. */
object CommandFormatter {
    private val safe = Regex("^[A-Za-z0-9_@%+=:,./-]+$")

    fun quote(arg: String): String =
        if (safe.matches(arg)) arg else "'" + arg.replace("'", "'\\''") + "'"

    fun format(args: List<String>, program: String = "ffmpeg"): String =
        (listOf(program) + args.map(::quote)).joinToString(" ")
}
