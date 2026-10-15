package app.squeeze.engine

enum class Severity { INFO, WARNING, ERROR }

/** A validation finding. [code] is stable (for localisation/tests), [message] is the English text. */
data class Issue(val severity: Severity, val code: String, val message: String)

val List<Issue>.hasErrors: Boolean get() = any { it.severity == Severity.ERROR }
