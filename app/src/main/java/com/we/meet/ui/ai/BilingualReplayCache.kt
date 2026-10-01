package com.we.meet.ui.ai

/** Bounded page-local PCM. Incomplete or evicted replies are never offered as full replays. */
internal class BilingualReplayCache(private val budget: Int = 120 * 48000, private val perReply: Int = 30 * 48000) {
    private class Audio {
        val chunks = mutableListOf<ByteArray>()
        var bytes = 0
        var complete = false
    }
    private val items = linkedMapOf<String, Audio>()
    private val discarded = mutableSetOf<String>()
    private var bytes = 0

    @Synchronized fun append(id: String, pcm: ByteArray) {
        if (id in discarded || pcm.isEmpty() || pcm.size % 2 != 0) return
        val item = items.getOrPut(id) { Audio() }
        if (item.complete) return
        if (item.bytes + pcm.size > perReply) { discard(id); return }
        while (bytes + pcm.size > budget || items.size > 100) {
            val oldest = items.keys.firstOrNull { it != id } ?: break
            discard(oldest)
        }
        if (bytes + pcm.size > budget) { discard(id); return }
        item.chunks += pcm.copyOf()
        item.bytes += pcm.size
        bytes += pcm.size
    }
    @Synchronized fun finish(id: String) { items[id]?.complete = true }
    @Synchronized fun ids(): Set<String> = items.filterValues { it.complete && it.bytes > 0 }.keys.toSet()
    @Synchronized fun get(id: String): List<ByteArray>? = items[id]?.takeIf { it.complete }?.chunks?.toList()
    @Synchronized fun clear() { items.clear(); discarded.clear(); bytes = 0 }
    @Synchronized fun discardIncomplete() { items.filterValues { !it.complete }.keys.toList().forEach(::discard) }
    private fun discard(id: String) { items.remove(id)?.let { bytes -= it.bytes }; discarded += id }
}
