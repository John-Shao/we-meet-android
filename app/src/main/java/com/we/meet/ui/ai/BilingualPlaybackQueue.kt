package com.we.meet.ui.ai

/** Keep concurrent model responses separate while bounding all queued PCM in bytes. */
internal class BilingualPlaybackQueue(private val maxBytes: Int = 60 * 48000) {
    data class Packet(val audio: ByteArray? = null)
    private class Item {
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

    @Synchronized fun poll(): Packet? {
        val first = items.entries.firstOrNull() ?: return null
        val chunk = first.value.chunks.removeFirstOrNull()
        if (chunk != null) {
            bytes -= chunk.size
            return Packet(chunk)
        }
        if (!first.value.ended) return null
        items.remove(first.key)
        return Packet()
    }

    @Synchronized fun clear() { items.clear(); bytes = 0 }
}
