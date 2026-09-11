package com.we.meet.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * The IM bridge calls the host app makes itself — 通讯录的两条「进会话」路径:
 * 「发消息」(拉直聊)与部门级「发起群聊」。Kept separate from feature-im's
 * ImApi so the app's contacts flow and the feature's `peer_uid` call sites
 * can't break each other.
 */
interface ImBridgeApi {

    @POST("api/v1.0/im/conversations/direct/")
    suspend fun createDirectConversation(
        @Body body: Map<String, String>,
    ): DirectConversationDto

    /**
     * 建群并把这些人拉进来。服务端会把调用者作为群主加进去,所以
     * [CreateGroupConversationBody.memberUserIds] **不该含自己**。
     *
     * 与直聊不同,群**不去重**:同一个部门点两次会建出两个群 —— 这正是确认框
     * 存在的理由。
     */
    @POST("api/v1.0/im/conversations/group/")
    suspend fun createGroupConversation(
        @Body body: CreateGroupConversationBody,
    ): GroupConversationDto
}

@JsonClass(generateAdapter = true)
data class DirectConversationDto(
    val cid: String,
    val type: String = "direct",
    val members: List<String> = emptyList(),
    val self_uid: String = "",
)

@JsonClass(generateAdapter = true)
data class CreateGroupConversationBody(
    @Json(name = "member_user_ids") val memberUserIds: List<String>,
    val name: String,
)

@JsonClass(generateAdapter = true)
data class GroupConversationDto(
    val cid: String,
    val type: String = "group",
    val members: List<String> = emptyList(),
    val self_uid: String = "",
)
