package com.we.meet.data.history

import android.content.Context
import android.util.Log
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "HistoryStore"
private const val FILE_NAME = "meeting_history.json"
private const val TMP_FILE_NAME = "meeting_history.json.tmp"
private const val MAX_ENTRIES = 200

/**
 * File-backed local store of meeting history entries. One JSON file in the
 * app's internal storage holds the full list; writes are serialized via a
 * Mutex and persisted atomically through a tmp-file rename.
 *
 * Entries are keyed by [HistoryEntry.roomId]; upserts preserve the existing
 * [HistoryEntry.firstJoinedAtMs] and union the [HistoryEntry.participants]
 * set, so rejoining the same meeting updates a single row rather than
 * creating a new one.
 */
class HistoryStore(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val adapter: JsonAdapter<List<HistoryEntry>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, HistoryEntry::class.java),
    )

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /** Newest entry first (by `max(firstJoinedAtMs, lastLeftAtMs ?: 0)`). */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    init {
        scope.launch { load() }
    }

    private fun file(): File = File(appContext.filesDir, FILE_NAME)
    private fun tmpFile(): File = File(appContext.filesDir, TMP_FILE_NAME)

    private suspend fun load() = mutex.withLock {
        val f = file()
        val loaded: List<HistoryEntry> = if (f.exists()) {
            runCatching { adapter.fromJson(f.readText())!! }
                .getOrElse { e ->
                    Log.w(TAG, "failed to parse history file, resetting", e)
                    emptyList()
                }
        } else {
            emptyList()
        }
        // Scrub legacy "—" / blank participant entries written before we
        // started filtering them out at the source.
        val scrubbed = loaded.map { entry ->
            val cleaned = entry.participants
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "—" }
            if (cleaned == entry.participants) entry else entry.copy(participants = cleaned)
        }
        _entries.value = scrubbed.sortedByDescending { it.sortKey() }
        if (scrubbed != loaded) writeLocked(_entries.value)
    }

    fun byId(roomId: String): HistoryEntry? = _entries.value.firstOrNull { it.roomId == roomId }

    /**
     * Insert or update an entry when the user successfully joins a room.
     *
     * If no prior entry exists for [roomId], creates one with
     * `firstJoinedAtMs = nowMs` and the provided metadata. If one exists,
     * updates metadata (name/slug/host/createdAt may have been unknown before),
     * adds [selfName] to participants, and clears `lastLeftAtMs` (we are
     * actively in the room again).
     */
    fun upsertOnJoin(
        roomId: String,
        name: String,
        slug: String,
        host: String?,
        createdAtMs: Long,
        selfName: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        scope.launch { mutateLocked { list ->
            val existing = list.firstOrNull { it.roomId == roomId }
            val next = if (existing == null) {
                HistoryEntry(
                    roomId = roomId,
                    name = name,
                    slug = slug,
                    host = host,
                    createdAtMs = createdAtMs,
                    firstJoinedAtMs = nowMs,
                    lastLeftAtMs = null,
                    participants = listOf(selfName),
                )
            } else {
                existing.copy(
                    // Keep the best-known values: never downgrade a known host
                    // or non-blank name to null/blank on rejoin.
                    name = name.ifBlank { existing.name },
                    slug = slug.ifBlank { existing.slug },
                    host = host ?: existing.host,
                    createdAtMs = existing.createdAtMs.takeIf { it > 0 } ?: createdAtMs,
                    lastLeftAtMs = null,
                    participants = mergeParticipants(existing.participants, listOf(selfName)),
                )
            }
            list.withUpserted(next)
        } }
    }

    /** Append observed participant names (dedupe, preserve first-seen order). */
    fun recordParticipants(roomId: String, names: List<String>) {
        if (names.isEmpty()) return
        scope.launch { mutateLocked { list ->
            val existing = list.firstOrNull { it.roomId == roomId } ?: return@mutateLocked list
            val merged = mergeParticipants(existing.participants, names)
            if (merged == existing.participants) list
            else list.withUpserted(existing.copy(participants = merged))
        } }
    }

    /** Mark that the user left this room at [leftAtMs]. */
    fun markLeft(roomId: String, leftAtMs: Long = System.currentTimeMillis()) {
        scope.launch { mutateLocked { list ->
            val existing = list.firstOrNull { it.roomId == roomId } ?: return@mutateLocked list
            list.withUpserted(existing.copy(lastLeftAtMs = leftAtMs))
        } }
    }

    private suspend fun mutateLocked(block: (List<HistoryEntry>) -> List<HistoryEntry>) {
        mutex.withLock {
            val current = _entries.value
            val next = block(current)
                .sortedByDescending { it.sortKey() }
                .take(MAX_ENTRIES)
            if (next == current) return@withLock
            _entries.value = next
            writeLocked(next)
        }
    }

    private suspend fun writeLocked(list: List<HistoryEntry>) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = tmpFile()
            tmp.writeText(adapter.toJson(list))
            if (!tmp.renameTo(file())) {
                // renameTo can fail on some filesystems if the target exists; fall back.
                file().writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { e -> Log.w(TAG, "failed to persist history", e) }
    }

    private fun List<HistoryEntry>.withUpserted(entry: HistoryEntry): List<HistoryEntry> {
        val others = filter { it.roomId != entry.roomId }
        return others + entry
    }

    private fun mergeParticipants(existing: List<String>, incoming: List<String>): List<String> {
        if (incoming.isEmpty()) return existing
        val set = LinkedHashSet(existing)
        incoming.forEach { name ->
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) set.add(trimmed)
        }
        return set.toList()
    }

    private fun HistoryEntry.sortKey(): Long =
        maxOf(firstJoinedAtMs, lastLeftAtMs ?: 0L, createdAtMs)
}
