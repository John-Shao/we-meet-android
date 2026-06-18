package com.we.meet.feature.im.data

/**
 * Tiny wrapper around [ImApi] so consumers (ViewModels / SDK token providers) don't
 * have to know about Retrofit. Holds no state — the underlying HTTP layer caches
 * nothing here; the jusi-light-im Client decides when to ask for a fresh token.
 */
internal class ImTokenRepository(private val api: ImApi) {

    /** Suspend round-trip to we-meet's `/api/v1.0/im/token/`. */
    suspend fun token(): ImTokenResponse = api.fetchToken()

    /** Suspend round-trip to we-meet's `/api/v1.0/im/conversations/direct/`. */
    suspend fun createDirect(peerUid: String): ImDirectConversationResponse =
        api.createDirectConversation(mapOf("peer_uid" to peerUid))
}
