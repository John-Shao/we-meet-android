package com.we.meet.feature.docs.data.net

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// ---- Documents (docs REST, session-cookie authenticated) ----

interface DocsApi {

    @GET("api/v1.0/documents/")
    suspend fun documents(
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null,
        @Query("is_creator_me") isCreatorMe: Boolean? = null,
        @Query("is_favorite") isFavorite: Boolean? = null,
        @Query("ordering") ordering: String? = null,
        @Query("q") q: String? = null,
    ): DocsPageDto

    @GET("api/v1.0/documents/{id}/")
    suspend fun document(@Path("id") id: String): DocumentDto

    @POST("api/v1.0/documents/")
    suspend fun createDocument(@Body body: DocsCreateRequest): DocumentDto

    @PATCH("api/v1.0/documents/{id}/")
    suspend fun renameDocument(
        @Path("id") id: String,
        @Body body: DocsRenameRequest,
    ): DocumentDto

    @DELETE("api/v1.0/documents/{id}/")
    suspend fun deleteDocument(@Path("id") id: String)

    @POST("api/v1.0/documents/{id}/favorite/")
    suspend fun addFavorite(@Path("id") id: String)

    @DELETE("api/v1.0/documents/{id}/favorite/")
    suspend fun removeFavorite(@Path("id") id: String)

    @GET("api/v1.0/documents/trashbin/")
    suspend fun trashbin(
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null,
    ): DocsPageDto

    @POST("api/v1.0/documents/{id}/restore/")
    suspend fun restore(@Path("id") id: String)

    @POST("api/v1.0/documents/{id}/move/")
    suspend fun move(
        @Path("id") id: String,
        @Body body: DocsMoveRequest,
    ): DocsMoveResponse

    @GET("api/v1.0/documents/search/")
    suspend fun search(@Query("q") q: String): DocsPageDto

    @GET("api/v1.0/documents/{id}/children/")
    suspend fun children(
        @Path("id") id: String,
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null,
    ): DocsPageDto

    @GET("api/v1.0/users/me/")
    suspend fun me(): DocsUserDto

    // ---- M2: read mode / comments / versions / share ----

    @GET("api/v1.0/documents/{id}/formatted-content/")
    suspend fun formattedContent(
        @Path("id") id: String,
        @Query("content_format") format: String = "json",
    ): DocsFormattedContentDto

    @PATCH("api/v1.0/documents/{id}/content/")
    suspend fun updateContent(
        @Path("id") id: String,
        @Body body: DocsContentUpdateRequest,
    )

    @GET("api/v1.0/documents/{id}/threads/")
    suspend fun threads(@Path("id") id: String): List<DocsThreadDto>

    @POST("api/v1.0/documents/{id}/threads/")
    suspend fun createThread(
        @Path("id") id: String,
        @Body body: DocsThreadCreateRequest,
    ): DocsThreadDto

    @DELETE("api/v1.0/documents/{id}/threads/{threadId}/")
    suspend fun deleteThread(
        @Path("id") id: String,
        @Path("threadId") threadId: String,
    )

    @POST("api/v1.0/documents/{id}/threads/{threadId}/resolve/")
    suspend fun resolveThread(
        @Path("id") id: String,
        @Path("threadId") threadId: String,
    )

    @POST("api/v1.0/documents/{id}/threads/{threadId}/unresolve/")
    suspend fun unresolveThread(
        @Path("id") id: String,
        @Path("threadId") threadId: String,
    )

    @POST("api/v1.0/documents/{id}/threads/{threadId}/comments/")
    suspend fun createComment(
        @Path("id") id: String,
        @Path("threadId") threadId: String,
        @Body body: DocsCommentCreateRequest,
    ): DocsCommentDto

    @DELETE("api/v1.0/documents/{id}/threads/{threadId}/comments/{commentId}/")
    suspend fun deleteComment(
        @Path("id") id: String,
        @Path("threadId") threadId: String,
        @Path("commentId") commentId: String,
    )

    @POST("api/v1.0/documents/{id}/threads/{threadId}/comments/{commentId}/reactions/")
    suspend fun addReaction(
        @Path("id") id: String,
        @Path("threadId") threadId: String,
        @Path("commentId") commentId: String,
        @Body body: DocsReactionRequest,
    )

    @DELETE("api/v1.0/documents/{id}/threads/{threadId}/comments/{commentId}/reactions/")
    suspend fun removeReaction(
        @Path("id") id: String,
        @Path("threadId") threadId: String,
        @Path("commentId") commentId: String,
        @Body body: DocsReactionRequest,
    )

    @GET("api/v1.0/documents/{id}/versions/")
    suspend fun versions(
        @Path("id") id: String,
        @Query("version_id") marker: String? = null,
    ): DocsVersionsDto

    @GET("api/v1.0/documents/{id}/versions/{versionId}/")
    suspend fun version(
        @Path("id") id: String,
        @Path("versionId") versionId: String,
    ): DocsVersionDto

    @GET("api/v1.0/documents/{id}/accesses/")
    suspend fun accesses(
        @Path("id") id: String,
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null,
    ): DocsAccessPageDto

    @POST("api/v1.0/documents/{id}/accesses/")
    suspend fun createAccess(
        @Path("id") id: String,
        @Body body: DocsAccessCreateRequest,
    ): DocsAccessDto

    @PATCH("api/v1.0/documents/{id}/accesses/{accessId}/")
    suspend fun updateAccess(
        @Path("id") id: String,
        @Path("accessId") accessId: String,
        @Body body: DocsAccessUpdateRequest,
    ): DocsAccessDto

    @DELETE("api/v1.0/documents/{id}/accesses/{accessId}/")
    suspend fun deleteAccess(
        @Path("id") id: String,
        @Path("accessId") accessId: String,
    )

    @GET("api/v1.0/documents/{id}/invitations/")
    suspend fun invitations(
        @Path("id") id: String,
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null,
    ): DocsInvitationPageDto

    @POST("api/v1.0/documents/{id}/invitations/")
    suspend fun createInvitation(
        @Path("id") id: String,
        @Body body: DocsInvitationCreateRequest,
    ): DocsInvitationDto

    @DELETE("api/v1.0/documents/{id}/invitations/{invitationId}/")
    suspend fun deleteInvitation(
        @Path("id") id: String,
        @Path("invitationId") invitationId: String,
    )

    @PUT("api/v1.0/documents/{id}/link-configuration/")
    suspend fun updateLinkConfiguration(
        @Path("id") id: String,
        @Body body: DocsLinkConfigurationRequest,
    )

    @POST("api/v1.0/documents/{id}/leave/")
    suspend fun leave(@Path("id") id: String)

    @GET("api/v1.0/documents/{id}/ask-for-access/")
    suspend fun accessRequests(
        @Path("id") id: String,
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null,
    ): DocsAccessRequestPageDto

    @POST("api/v1.0/documents/{id}/ask-for-access/")
    suspend fun createAccessRequest(
        @Path("id") id: String,
        @Body body: DocsAccessRequestCreate,
    )

    @GET("api/v1.0/users/")
    suspend fun searchUsers(
        @Query("q") q: String? = null,
        @Query("document_id") documentId: String? = null,
    ): List<DocsUserDto>
}

// ---- Meet-side ticket (host-authenticated) ----

interface DocsTicketApi {
    @POST("api/v1.0/docs/session/")
    suspend fun createSession(@Body body: DocsTicketRequest): DocsTicketResponse
}

// ---- DTOs ----

@JsonClass(generateAdapter = true)
data class DocsTicketRequest(val next: String)

@JsonClass(generateAdapter = true)
data class DocsTicketResponse(val url: String? = null)

/** DRF PageNumberPagination envelope — shared by list / search / children / trashbin. */
@JsonClass(generateAdapter = true)
data class DocsPageDto(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<DocumentDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DocumentDto(
    val id: String = "",
    val title: String? = null,
    val excerpt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    /** Creator id (UUID string) — list/detail serializers render the FK as a plain id. */
    val creator: String? = null,
    val abilities: DocsAbilitiesDto = DocsAbilitiesDto(),
    @Json(name = "is_favorite") val isFavorite: Boolean = false,
    @Json(name = "user_role") val userRole: String? = null,
    val numchild: Int = 0,
    val depth: Int = 0,
    val path: String = "",
    @Json(name = "deleted_at") val deletedAt: String? = null,
    @Json(name = "link_reach") val linkReach: String? = null,
    @Json(name = "link_role") val linkRole: String? = null,
    @Json(name = "computed_link_reach") val computedLinkReach: String? = null,
    @Json(name = "computed_link_role") val computedLinkRole: String? = null,
    /** Search results embed the parent document. */
    val parent: DocumentDto? = null,
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: ""
    val isFolder: Boolean
        get() = numchild > 0
}

@JsonClass(generateAdapter = true)
data class DocsAbilitiesDto(
    @Json(name = "partial_update") val partialUpdate: Boolean = false,
    val update: Boolean = false,
    val destroy: Boolean = false,
    val move: Boolean = false,
    val restore: Boolean = false,
    val favorite: Boolean = false,
    val duplicate: Boolean = false,
    val retrieve: Boolean = false,
    @Json(name = "link_configuration") val linkConfiguration: Boolean = false,
) {
    val canRename: Boolean get() = partialUpdate || update
}

@JsonClass(generateAdapter = true)
data class DocsUserDto(
    val id: String = "",
    @Json(name = "full_name") val fullName: String? = null,
    @Json(name = "short_name") val shortName: String? = null,
    val email: String? = null,
) {
    val displayName: String
        get() = fullName?.takeIf { it.isNotBlank() }
            ?: shortName?.takeIf { it.isNotBlank() }
            ?: email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: ""
}

@JsonClass(generateAdapter = true)
data class DocsCreateRequest(val title: String)

@JsonClass(generateAdapter = true)
data class DocsRenameRequest(val title: String)

@JsonClass(generateAdapter = true)
data class DocsMoveRequest(
    @Json(name = "target_document_id") val targetDocumentId: String,
    val position: String,
)

@JsonClass(generateAdapter = true)
data class DocsMoveResponse(val message: String? = null)

/** Move positions — mirrors docs' MoveNodePositionChoices. */
object DocsMovePositions {
    const val FIRST_CHILD = "first-child"
    const val LAST_CHILD = "last-child"
    const val LEFT = "left"
    const val RIGHT = "right"
}
