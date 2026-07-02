package com.we.meet.data.api

import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * The one IM bridge call the host app makes itself (通讯录 "发消息"): create-or-get
 * a direct conversation by we-meet user id. Kept separate from feature-im's
 * ImApi so the app's contacts flow and the feature's `peer_uid` call sites
 * can't break each other.
 */
interface ImBridgeApi {

    @POST("api/v1.0/im/conversations/direct/")
    suspend fun createDirectConversation(
        @Body body: Map<String, String>,
    ): DirectConversationDto
}

@JsonClass(generateAdapter = true)
data class DirectConversationDto(
    val cid: String,
    val type: String = "direct",
    val members: List<String> = emptyList(),
    val self_uid: String = "",
)
