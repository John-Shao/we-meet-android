package com.we.meet.ui.ai

/** Keep concurrent model responses separate while bounding all queued PCM in bytes. */
internal class BilingualPlaybackQueue(private val maxBytes: Int = 60 * 48000) {
    data class Packet(
        val audio: ByteArray? = null,
        val replay: Boolean = false,
        val id: String? = null,
    )
    private class Item(val replay: Boolean = false) {
        val chunks = ArrayDeque<ByteArray>()
        var ended = false
    }
    private val items = linkedMapOf<String, Item>()
    private var bytes = 0

    @Synchronized fun offer(id: String, pcm: ByteArray) {
        require(pcm.isNotEmpty() && pcm.size <= 24000 && pcm.size % 2 == 0)
        check(bytes + pcm.size <= maxBytes)
        check(id in items || items.size < 16)
        val item = items.getOrPut(id) { Item() }
        check(!item.ended)
        item.chunks.addLast(pcm)
        bytes += pcm.size
    }

    @Synchronized fun finish(id: String) { items[id]?.ended = true }

    @Synchronized fun replay(chunks: List<ByteArray>): Boolean {
        if (items.isNotEmpty() || chunks.isEmpty() || chunks.sumOf { it.size } > maxBytes) return false
        val item = Item(replay = true)
        chunks.forEach { item.chunks.addLast(it) }
        item.ended = true
        items["local-replay-${java.util.UUID.randomUUID()}"] = item
        bytes = chunks.sumOf { it.size }
        return true
    }

    @Synchronized fun poll(): Packet? {
        val first = items.entries.firstOrNull() ?: return null
        val chunk = first.value.chunks.removeFirstOrNull()
        if (chunk != null) {
            bytes -= chunk.size
            return Packet(chunk, first.value.replay, first.key)
        }
        if (!first.value.ended) return null
        items.remove(first.key)
        return Packet(replay = first.value.replay, id = first.key)
    }

    @Synchronized fun clear() { items.clear(); bytes = 0 }
    @Synchronized fun isEmpty() = items.isEmpty()
}
