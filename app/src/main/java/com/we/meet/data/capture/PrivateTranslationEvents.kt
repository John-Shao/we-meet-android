package com.we.meet.data.capture

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.dto.PrivateTranslationRunDto
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class PrivateTranslationEvent(
    @Json(name = "run_id") val runId: String, val generation: Long, val type: String,
    val sequence: Long? = null, val direction: String? = null, val awaiting: Boolean? = null,
    @Json(name = "audio_track_sid") val audioTrackSid: String? = null,
    @Json(name = "response_id") val responseId: String? = null,
    @Json(name = "item_id") val itemId: String? = null,
    val text: String? = null, val stash: String? = null,
) { override fun toString() = "PrivateTranslationEvent(<private>)" }

data class PrivateTranslationRow(val id: String, val direction: String, val text: String, val stash: String, val final: Boolean) {
    override fun toString() = "PrivateTranslationRow(<private>)"
}

/** Bounded data-channel text is separate from originals and never executes or renders HTML. */
object PrivateTranslationEvents {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(PrivateTranslationEvent::class.java)
    fun decode(payload: ByteArray, identity: String, agent: Boolean, run: PrivateTranslationRunDto): PrivateTranslationEvent? {
        if (!agent || identity.length > 256 || !identity.startsWith("translation-${run.id}-") || payload.size !in 2..14000 || run.state !in setOf("starting", "translating", "stopping")) return null
        return runCatching {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString()
            requireNotNull(adapter.fromJson(text)).also { event ->
                require(event.runId == run.id && event.generation == run.generation)
                val direction = event.direction in setOf("forward", "reverse")
                when (event.type) {
                    "ready" -> {
                        require(event.sequence != null && event.sequence in 0..9007199254740991L && event.awaiting != null && (event.direction == null || direction))
                        require(event.audioTrackSid == null || event.audioTrackSid.matches(Regex("TR_[A-Za-z0-9_-]{1,124}")))
                    }
                    "turn_completed" -> require(direction)
                    "target_candidate", "target_final" -> {
                        require(direction && event.responseId?.length in 1..128 && event.itemId?.length in 1..128)
                        require(event.text != null && event.text.length <= 20000 && (event.stash?.length ?: 0) <= 20000)
                    }
                    else -> error("Unsupported translation event")
                }
            }
        }.getOrNull()
    }
    fun update(rows: List<PrivateTranslationRow>, event: PrivateTranslationEvent): List<PrivateTranslationRow> {
        if (event.type !in setOf("target_candidate", "target_final")) return rows
        val id = "${event.direction}:${event.responseId}:${event.itemId}"
        val old = rows.find { it.id == id }
        if (old?.final == true) return rows
        val row = PrivateTranslationRow(id, requireNotNull(event.direction), requireNotNull(event.text), event.stash.orEmpty(), event.type == "target_final")
        return (if (old == null) rows + row else rows.map { if (it.id == id) row else it }).takeLast(60)
    }
}
