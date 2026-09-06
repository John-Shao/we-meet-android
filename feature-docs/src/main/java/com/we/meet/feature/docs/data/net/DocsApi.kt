package com.we.meet.feature.docs.data.net

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
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
    val creator: DocsUserDto? = null,
    val abilities: DocsAbilitiesDto = DocsAbilitiesDto(),
    @Json(name = "is_favorite") val isFavorite: Boolean = false,
    @Json(name = "user_role") val userRole: String? = null,
    val numchild: Int = 0,
    val depth: Int = 0,
    val path: String = "",
    @Json(name = "deleted_at") val deletedAt: String? = null,
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
