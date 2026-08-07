package com.we.meet.feature.im.data

/**
 * Typed facade over [ImApi] so consumers (ImSession / ViewModels / the SDK's
 * tokenProvider) never touch Retrofit or raw Maps. Stateless — caching lives in
 * UserDirectory / MediaResolver, and the jusi-light-im Client decides when to
 * re-mint its token.
 */
internal class ImBridgeRepository(private val api: ImApi) {

    /**
     * 点一个卡片按钮(A2)。`clickId` 是幂等键 —— 同一次点击重试带同一个值,
     * 服务端重放上次结果而不是再记一笔(与入站 webhook 的 X-Request-Id 同
     * 一个思路)。409 = 这个 once 块已被别人定局。
     */
    suspend fun clickCardButton(mid: Long, buttonId: String, clickId: String) =
        api.clickCardButton(mid, mapOf("button_id" to buttonId, "click_id" to clickId))

    /** 批量拉一屏卡片的叠加层 —— 往上翻到窗口外的老卡时用得上。 */
    suspend fun cardStates(mids: List<Long>) = api.cardStates(mapOf("mids" to mids))

    /** Mint an IM JWT via we-meet's `api/v1.0/im/token/`. */
    suspend fun token(): ImTokenResponse = api.fetchToken()

    /** Create-or-get a direct conversation by jusi uid (legacy/raw flow). */
    suspend fun createDirect(peerUid: String): ImDirectConversationResponse =
        api.createDirectConversation(mapOf("peer_uid" to peerUid))

    /** Create-or-get a direct conversation by we-meet user id (contact-picker flow). */
    suspend fun createDirectByUserId(peerUserId: String): ImDirectConversationResponse =
        api.createDirectConversation(mapOf("peer_user_id" to peerUserId))

    /** Create a group; the caller becomes owner. Not deduplicated. */
    suspend fun createGroup(memberUserIds: List<String>, name: String): ImGroupConversationResponse =
        api.createGroupConversation(mapOf("member_user_ids" to memberUserIds, "name" to name))

    suspend fun addMembers(cid: String, memberUserIds: List<String>) {
        api.addMembers(mapOf("cid" to cid, "member_user_ids" to memberUserIds))
    }

    suspend fun removeMember(cid: String, memberUserId: String) {
        api.removeMember(mapOf("cid" to cid, "member_user_id" to memberUserId))
    }

    /**
     * Rename / re-describe a group. jusi stores meta wholesale — pass BOTH the
     * new and the unchanged field so nothing is clobbered.
     */
    suspend fun updateGroupMeta(cid: String, name: String, description: String, kind: String) {
        api.updateGroupMeta(
            mapOf("cid" to cid, "name" to name, "description" to description, "kind" to kind)
        )
    }

    /** Best-effort "left the group" system message; call while still a member. */
    suspend fun announceLeave(cid: String) {
        runCatching { api.announceLeave(mapOf("cid" to cid)) }
    }

    /** Map IM uids → display identities (org-scoped; unknown uids absent). */
    /** P4 通话多人宫格: LiveKit identity(=sub) → 目录显示名/头像(组织内)。 */
    suspend fun resolveSubs(subs: Collection<String>): Map<String, ImUserInfo> =
        if (subs.isEmpty()) emptyMap()
        else api.resolveSubs(mapOf("subs" to subs.toList()))

    suspend fun resolveUsers(imUids: Collection<String>): Map<String, ImUserInfo> =
        if (imUids.isEmpty()) emptyMap()
        else api.resolveUsers(mapOf("im_uids" to imUids.toList()))

    suspend fun imageUploadUrl(contentType: String, size: Long): UploadUrlResponse =
        api.imageUploadUrl(mapOf("content_type" to contentType, "size" to size))

    suspend fun fileUploadUrl(name: String, contentType: String, size: Long): UploadUrlResponse =
        api.fileUploadUrl(mapOf("name" to name, "content_type" to contentType, "size" to size))

    suspend fun audioUploadUrl(contentType: String, size: Long, filename: String): UploadUrlResponse =
        api.audioUploadUrl(
            mapOf("content_type" to contentType, "size" to size, "filename" to filename),
        )

    /** Map object keys → presigned GET URLs (~1h validity). */
    suspend fun resolveMedia(objectKeys: Collection<String>): Map<String, String> =
        if (objectKeys.isEmpty()) emptyMap()
        else api.resolveMedia(mapOf("object_keys" to objectKeys.toList()))

    /** 分享云文档到聊天:给会话成员授文档只读(best-effort)。 */
    suspend fun grantDocAccess(docId: String, cids: Collection<String>) {
        api.grantDocAccess(mapOf("doc_id" to docId, "cids" to cids.toList()))
    }

    /** P1-M3 消息全文检索(q≥2 才有意义;beforeMid 翻更旧一页)。 */
    suspend fun searchMessages(
        q: String,
        cid: String? = null,
        limit: Int? = null,
        beforeMid: Long? = null,
    ): ImSearchResponse = api.searchMessages(q, cid, limit, beforeMid)

    // ---- 群机器人。类型化门面:后端若改成 action 风格,只动 ImApi,VM/UI 不变。 ----

    suspend fun listBots(cid: String): List<ImBotDto> = api.listBots(cid)

    suspend fun createBot(
        cid: String,
        name: String,
        description: String,
        avatarColorIndex: Int,
    ): ImBotDto = api.createBot(
        mapOf(
            "cid" to cid,
            "name" to name,
            "description" to description,
            "avatar_color_index" to avatarColorIndex,
        ),
    )

    /** 只发要改的键 —— 后端按 key 是否出现决定改不改。 */
    suspend fun updateBot(
        id: String,
        name: String? = null,
        description: String? = null,
        avatarColorIndex: Int? = null,
        signVerifyEnabled: Boolean? = null,
        keywords: List<String>? = null,
        ipAllowlist: List<String>? = null,
        isActive: Boolean? = null,
        /** 空串 = 关掉回调。所以判的是 null 而不是 isNotBlank。 */
        callbackUrl: String? = null,
        callbackIncludeIdentity: Boolean? = null,
    ): ImBotDto = api.updateBot(
        id,
        buildMap {
            name?.let { put("name", it) }
            description?.let { put("description", it) }
            avatarColorIndex?.let { put("avatar_color_index", it) }
            signVerifyEnabled?.let { put("sign_verify_enabled", it) }
            keywords?.let { put("keywords", it) }
            ipAllowlist?.let { put("ip_allowlist", it) }
            isActive?.let { put("is_active", it) }
            callbackUrl?.let { put("callback_url", it) }
            callbackIncludeIdentity?.let { put("callback_include_identity", it) }
        },
    )

    suspend fun deleteBot(id: String) {
        api.deleteBot(id)
    }

    suspend fun botSecret(id: String): String = api.fetchBotSecret(id).secret

    suspend fun botCallbackSecret(id: String): String =
        api.fetchBotCallbackSecret(id).secret

    suspend fun resetBotSecret(id: String): String = api.resetBotSecret(id).secret
}
