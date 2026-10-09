package com.mvrk.vrka.mi5g.sync

import kotlin.math.abs

/** Pure merge logic (unit-tested). */
object SyncMerge {
    data class Result(
        val merged: SyncSnapshot,
        /** Settings whose remote value won and must be written locally. */
        val settingsToApply: Map<String, Any>,
        /** Remote history entries to insert/update locally (live records). */
        val historyUpserts: List<HistoryRecord>,
        /** Local history ids to delete (remote tombstones won). */
        val historyDeletes: Set<String>,
        /** Remote ids that are duplicates of a local entry (same URL + creation time). */
        val aliases: Map<String, String>,
    ) {
        val changedLocal: Boolean get() = settingsToApply.isNotEmpty() || historyUpserts.isNotEmpty() || historyDeletes.isNotEmpty()
    }

    const val DEDUPE_WINDOW_MS = 2_000L

    /** Last-writer-wins per setting key; ties keep the local value (no churn). */
    fun mergeSettings(local: Map<String, SettingValue>, remote: Map<String, SettingValue>): Pair<Map<String, SettingValue>, Map<String, Any>> {
        val merged = LinkedHashMap<String, SettingValue>()
        val apply = LinkedHashMap<String, Any>()
        (local.keys + remote.keys).filter(SyncPolicy::isSyncable).forEach { k ->
            val l = local[k]; val r = remote[k]
            when {
                l == null && r != null -> { merged[k] = r; apply[k] = r.value }
                r == null && l != null -> merged[k] = l
                l != null && r != null -> if (r.updatedAt > l.updatedAt) {
                    merged[k] = r
                    if (r.value != l.value) apply[k] = r.value
                } else merged[k] = l
            }
        }
        return merged to apply
    }

    /** Record-level LWW. Tombstones win ties so a deletion is never resurrected by an equal-time edit. */
    fun pick(a: HistoryRecord, b: HistoryRecord): HistoryRecord {
        val ta = if (a.deleted) (a.deletedAt ?: a.updatedAt) else a.updatedAt
        val tb = if (b.deleted) (b.deletedAt ?: b.updatedAt) else b.updatedAt
        return when {
            ta > tb -> a
            tb > ta -> b
            a.deleted -> a
            b.deleted -> b
            else -> a
        }
    }

    fun merge(local: SyncSnapshot, remote: SyncSnapshot): Result {
        val (settings, settingsToApply) = mergeSettings(local.settings, remote.settings)

        // Dedupe: a remote live entry with a new id but the same URL + creation time as a local live
        // entry is the same download. Both devices converge on the lexicographically smaller id; the
        // other id becomes a tombstone so it never comes back.
        val localH = HashMap(local.history)
        val remoteH = HashMap(remote.history)
        val extraTombstones = HashMap<String, HistoryRecord>()
        val aliases = HashMap<String, String>()
        val renamedLocal = HashSet<String>() // new ids that replace a local id → must be upserted
        val deletes = mutableSetOf<String>()
        remote.history.values.filter { !it.deleted && it.id !in local.history }.sortedBy { it.id }.forEach { r ->
            if (r.url.isBlank()) return@forEach
            val l = localH.values.firstOrNull { l ->
                !l.deleted && l.id !in remote.history && l.id != r.id &&
                    l.url == r.url && abs(l.createdAt - r.createdAt) <= DEDUPE_WINDOW_MS
            } ?: return@forEach
            if (l.id < r.id) {
                remoteH.remove(r.id)
                remoteH[l.id] = r.copy(id = l.id)
                extraTombstones[r.id] = HistoryRecord.tombstone(r.id, r.updatedAt)
                aliases[r.id] = l.id
            } else {
                localH.remove(l.id)
                localH[r.id] = l.copy(id = r.id)
                extraTombstones[l.id] = HistoryRecord.tombstone(l.id, l.updatedAt)
                aliases[l.id] = r.id
                renamedLocal += r.id
                deletes += l.id
            }
        }

        val merged = LinkedHashMap<String, HistoryRecord>()
        val upserts = mutableListOf<HistoryRecord>()
        (localH.keys + remoteH.keys).forEach { id ->
            val l = localH[id]; val r = remoteH[id]
            val winner = when {
                l == null -> r!!
                r == null -> l
                else -> pick(l, r)
            }
            merged[id] = winner
            if (id in renamedLocal) {
                if (!winner.deleted) upserts += winner
            } else if (winner !== l) {
                if (winner.deleted) { if (l != null && !l.deleted) deletes += id }
                else if (l == null || l.deleted || winner != l) upserts += winner
            }
        }
        merged.putAll(extraTombstones)
        return Result(
            merged = SyncSnapshot(settings, merged, local.devices + remote.devices, remote.fileId ?: local.fileId, maxOf(local.updatedAt, remote.updatedAt)),
            settingsToApply = settingsToApply,
            historyUpserts = upserts,
            historyDeletes = deletes,
            aliases = aliases,
        )
    }

    /** True when the merged snapshot has something the remote file lacks (needs a write). */
    fun needsPush(merged: SyncSnapshot, remote: SyncSnapshot): Boolean =
        merged.settings != remote.settings || merged.history != remote.history || remote.fileId == null

    /** Drops tombstones older than [maxAgeMs] so the file does not grow forever. */
    fun pruneTombstones(s: SyncSnapshot, now: Long, maxAgeMs: Long = 180L * 24 * 3600 * 1000): SyncSnapshot =
        s.copy(history = s.history.filterValues { !it.deleted || now - (it.deletedAt ?: it.updatedAt) < maxAgeMs })
}

/**
 * Per-key updatedAt tracking for settings: compares the current values with the last known ones
 * and stamps every changed key with [now]. Keys that disappeared keep their last stamp.
 */
object SettingsStamps {
    data class State(val values: Map<String, Any>, val stamps: Map<String, Long>)

    fun stamp(previous: State, current: Map<String, Any>, now: Long): State {
        val stamps = HashMap(previous.stamps)
        current.forEach { (k, v) ->
            if (!SyncPolicy.isSyncable(k)) return@forEach
            if (previous.values[k] != v || k !in stamps) stamps[k] = if (k in previous.values || previous.values.isNotEmpty()) now else 0L
        }
        return State(current.filterKeys(SyncPolicy::isSyncable), stamps.filterKeys(SyncPolicy::isSyncable))
    }

    fun snapshot(state: State): Map<String, SettingValue> =
        state.values.filter { (k, v) -> SyncPolicy.isSyncableValue(k, v) }
            .mapValues { (k, v) -> SettingValue(v, state.stamps[k] ?: 0L) }
}
