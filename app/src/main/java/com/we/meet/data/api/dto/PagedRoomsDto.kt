package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * DRF PageNumberPagination envelope for `GET /api/v1.0/rooms/`.
 *
 * Backend filters list responses to rooms the authenticated user is a
 * member of (see `RoomViewSet.list` → `filter(users=user)`), so calling
 * this without query params already yields "mine" semantics.
 *
 * `next`/`previous` are full URLs ("https://.../rooms/?page=2"); the
 * mobile client only consumes the first page for now, so they exist
 * only to surface "is there more" via a non-null `next`.
 */
@JsonClass(generateAdapter = true)
data class PagedRoomsDto(
    val count: Int,
    val next: String?,
    val previous: String?,
    val results: List<RoomDto>,
)
