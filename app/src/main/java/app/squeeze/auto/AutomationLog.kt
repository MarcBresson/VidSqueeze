package app.squeeze.auto

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One command received through the automation API, shown in the visible automation log. */
@Serializable
data class AutomationLogEntry(val timeMs: Long, val action: String, val accepted: Boolean, val detail: String)

object AutomationLogJson {
    const val MAX_ENTRIES = 100
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(AutomationLogEntry.serializer())

    fun encode(entries: List<AutomationLogEntry>): String = json.encodeToString(serializer, entries)

    fun decode(text: String?): List<AutomationLogEntry> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())

    /** Newest first, capped. */
    fun append(entries: List<AutomationLogEntry>, entry: AutomationLogEntry): List<AutomationLogEntry> =
        (listOf(entry) + entries).take(MAX_ENTRIES)
}
