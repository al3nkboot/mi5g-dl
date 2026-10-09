package com.mvrk.vrka.mi5g.sync

import org.json.JSONObject
import java.io.File

/** Mi5G Dl: deletion markers for history entries (id → deletedAt) so deletions sync across devices. */
class TombstoneStore(private val file: File) {
    @Synchronized
    fun all(): Map<String, Long> = runCatching {
        if (!file.isFile) return emptyMap()
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { o.optLong(it) }
    }.getOrDefault(emptyMap())

    /** Records deletions; keeps the earliest known deletion time unless [overwrite]. */
    @Synchronized
    fun record(ids: Map<String, Long>, overwrite: Boolean = false) {
        if (ids.isEmpty()) return
        val current = all().toMutableMap()
        ids.forEach { (id, at) ->
            val prev = current[id]
            current[id] = if (prev == null || overwrite) at else minOf(prev, at)
        }
        write(current)
    }

    @Synchronized
    fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val current = all().toMutableMap()
        if (current.keys.removeAll(ids.toSet())) write(current)
    }

    @Synchronized
    fun prune(olderThan: Long) {
        val current = all()
        val kept = current.filterValues { it >= olderThan }
        if (kept.size != current.size) write(kept)
    }

    private fun write(map: Map<String, Long>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject(map as Map<*, *>).toString())
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }
}
