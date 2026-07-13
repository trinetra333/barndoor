package com.barndoor.app.dns

data class QueryLog(
    val timestamp: Long,
    val domain: String,
    val resolverName: String,
    val appLabel: String,
    val protocol: String, // "UDP", "DoT (UDP)", "DoT (TCP)"
    val durationMs: Long,
    val success: Boolean
)

/** In-memory only, capped ring buffer — nothing here is written to disk. */
object QueryLogStore {
    private const val MAX_ENTRIES = 300
    private val logs = mutableListOf<QueryLog>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun add(entry: QueryLog) {
        synchronized(logs) {
            logs.add(0, entry)
            while (logs.size > MAX_ENTRIES) logs.removeAt(logs.size - 1)
        }
        listeners.forEach { it() }
    }

    fun snapshot(): List<QueryLog> = synchronized(logs) { logs.toList() }

    fun clear() {
        synchronized(logs) { logs.clear() }
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)
}
