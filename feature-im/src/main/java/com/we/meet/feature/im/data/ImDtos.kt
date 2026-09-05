package com.we.meet.feature.im.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * DTOs for the we-meet → jusi-light-im bridge endpoints (core/api/im.py).
 * Mirror of the web client's ApiIm.ts. Non-id fields are defaulted/nullable —
 * the shared Moshi setup is reflection-based and throws on missing non-null
 * fields, and backend serializers evolve independently.
 */

@JsonClass(generateAdapter = true)
internal data class ImTokenResponse(
    val uid: String,
    val token: String,
    val ws_url: String,
    val expires_at: Long,
)

/** Result of POST im/conversations/direct/ — create-or-get 1-on-1 conv. */
@JsonClass(generateAdapter = true)
internal data class ImDirectConversationResponse(
    val cid: String,
    val type: String = "direct",
    val members: List<String> = emptyList(),
    val self_uid: String = "",
)

/** Result of POST im/conversations/group/ — create a group conv. */
@JsonClass(generateAdapter = true)
internal data class ImGroupConversationResponse(
    val cid: String,
    val type: String = "group",
    @Json(name = "owner_uid") val ownerUid: String = "",
    val members: List<String> = emptyList(),
    val self_uid: String = "",
)

/** One entry of POST im/users/resolve/ — display identity for an IM uid. */
@JsonClass(generateAdapter = true)
data class ImUserInfo(
    val id: String = "",
    @Json(name = "full_name") val fullName: String = "",
    @Json(name = "short_name") val shortName: String = "",
    /** Presigned avatar GET URL; empty when the user has no uploaded avatar. */
    @Json(name = "avatar_url") val avatarUrl: String? = null,
    /**
     * 该成员在本组织已无在职关系(P10 离职流程)。
     *
     * 解析端点刻意**不**把离职者剔掉 —— 那样历史消息里的名字会退回裸 uid,
     * 比「张三(已离职)」糟糕得多。所以人照常解析,由这个 flag 决定怎么标。
     * 默认 false,老后端不返回该字段时按在职处理。
     */
    val left: Boolean = false,
    /**
     * 该 uid 是群机器人(jusi role='bot')。机器人不是 User —— 后端在同一个
     * 解析端点里额外查一遍机器人表,于是气泡拿头像/名字/描述副标题不用多发
     * 一次请求。老后端不返回该字段,默认 false。
     */
    @Json(name = "is_bot") val isBot: Boolean = false,
    /** 机器人的一行说明,挂在气泡的发送人名字后面。真人不返回。 */
    val description: String = "",
) {
    val displayName: String
        get() = fullName.ifBlank { shortName }
}

/**
 * One pre-resolved tile for the 9-grid group avatar. Resolved eagerly in the
 * VM (not via a callback that reads a directory snapshot at composition), so a
 * later resolve changes this value and Compose actually recomposes the avatar.
 */
data class GroupTile(
    val uid: String,
    val name: String,
    val avatarUrl: String?,
)

/** Result of POST im/{images,files,audio}/upload-url/ — presigned PUT target. */
@JsonClass(generateAdapter = true)
internal data class UploadUrlResponse(
    @Json(name = "upload_url") val uploadUrl: String,
    @Json(name = "object_key") val objectKey: String,
    @Json(name = "expires_in") val expiresIn: Long = 0,
    val headers: Map<String, String> = emptyMap(),
)

/** Result of confirming or removing a group's custom avatar. */
@JsonClass(generateAdapter = true)
internal data class GroupAvatarResponse(
    val cid: String,
    @Json(name = "avatar_url") val avatarUrl: String = "",
)

@JsonClass(generateAdapter = true)
data class ImDraftReplyDto(
    val mid: String = "",
    val sender: String = "",
    val summary: String = "",
)

@JsonClass(generateAdapter = true)
data class ImRecentEmojiDto(
    val kind: String = "unicode",
    val value: String? = null,
    val id: String? = null,
    val key: String? = null,
    val name: String? = null,
)

@JsonClass(generateAdapter = true)
internal data class ImPreferenceDto(
    @Json(name = "recent_emojis") val recentEmojis: List<ImRecentEmojiDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ImCustomEmojiDto(
    val id: String = "",
    val name: String = "",
    val key: String = "",
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val animated: Boolean = false,
)

/** P1-M3 消息全文检索:GET im/search/ 的命中项(镜像 web ImSearchItem)。 */
@JsonClass(generateAdapter = true)
internal data class ImSearchItem(
    val mid: Long,
    val cid: String,
    @Json(name = "sender_uid") val senderUid: String = "",
    val seq: Long = 0,
    @Json(name = "content_type") val contentType: String = "text",
    val body: String = "",
    @Json(name = "created_at") val createdAt: Long = 0,
)

/** P1-M3 消息全文检索:响应体(items 时间倒序 + 翻页游标)。 */
@JsonClass(generateAdapter = true)
internal data class ImSearchResponse(
    val items: List<ImSearchItem> = emptyList(),
    @Json(name = "next_before_mid") val nextBeforeMid: Long? = null,
)

// ---- 群机器人 (core/api/im_bots.py) ----

/**
 * 一个机器人在某个群里的安装。
 *
 * 凭据字段(webhook / 签名 / 关键词 / IP 白名单)对**非群主**返回 null ——
 * 每个成员都该看得见群里有什么在说话,但不该拿到往里发消息的钥匙。
 */
@JsonClass(generateAdapter = true)
internal data class ImBotDto(
    val id: String = "",
    val cid: String = "",
    /** custom = 自定义 webhook 机器人;builtin = 内置助手,不可移除。 */
    val kind: String = "custom",
    val slug: String = "",
    /** jusi uid —— 与消息的 sender_uid 对得上。 */
    val uid: String = "",
    val name: String = "",
    val description: String = "",
    @Json(name = "avatar_url") val avatarUrl: String? = null,
    @Json(name = "avatar_color_index") val avatarColorIndex: Int = 0,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "disabled_reason") val disabledReason: String = "",
    @Json(name = "created_at") val createdAt: String = "",
    @Json(name = "last_used_at") val lastUsedAt: String? = null,
    @Json(name = "message_count") val messageCount: Int = 0,
    @Json(name = "webhook_url") val webhookUrl: String? = null,
    @Json(name = "sign_verify_enabled") val signVerifyEnabled: Boolean? = null,
    val keywords: List<String>? = null,
    @Json(name = "ip_allowlist") val ipAllowlist: List<String>? = null,
    /**
     * 出站回调 (A3)。地址挂在**机器人**上而不是按钮里 —— 按钮里带 URL 等于
     * 任何拿到 webhook token 的人都能把服务器变成任意 HTTP 代理。
     *
     * `callback_secret` 后端不下发:那是签出站请求用的密钥,不出服务器。
     */
    @Json(name = "callback_url") val callbackUrl: String? = null,
    @Json(name = "callback_include_identity") val callbackIncludeIdentity: Boolean? = null,
    /** 连续失败多次会自己变 false;重新保存地址即可恢复。 */
    @Json(name = "callback_enabled") val callbackEnabled: Boolean? = null,
    /**
     * 最近一次回调失败的**桶**(timeout / refused / unreachable / blocked),
     * 上一次成功则为空串。**永远不是上游响应原文** —— 那是 SSRF 的信息回传通道。
     */
    @Json(name = "callback_last_error") val callbackLastError: String? = null,
) {
    /** 非群主拿不到凭据,详情页据此只读展示。 */
    val canManage: Boolean get() = webhookUrl != null || kind == "builtin"
}

@JsonClass(generateAdapter = true)
internal data class ImBotSecretResponse(val secret: String = "")

@JsonClass(generateAdapter = true)
internal data class ImBotTokenResponse(
    @Json(name = "webhook_url") val webhookUrl: String = "",
)
