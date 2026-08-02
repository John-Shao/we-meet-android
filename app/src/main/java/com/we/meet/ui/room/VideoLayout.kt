package com.we.meet.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import com.we.meet.ui.theme.Dimens
import io.livekit.android.room.Room

/** Pick bucket-based per-page tile count + (cols, rows) based on orientation. */
private data class PageLayout(val cols: Int, val rows: Int) {
    val perPage: Int get() = cols * rows
}

/**
 * For counts that fit in a single page (≤ pageCapacity), we allow bespoke
 * layouts (e.g. 3 = 1 big + 2 small in portrait). Beyond that we fall back
 * to the uniform grid with pagination. See [galleryPageLayout].
 */
private fun galleryPageLayout(isPortrait: Boolean, count: Int): PageLayout {
    return if (isPortrait) {
        when (count) {
            1 -> PageLayout(1, 1)
            2 -> PageLayout(1, 2)
            3 -> PageLayout(1, 1) // marker; rendered as custom 1+2 layout below
            4 -> PageLayout(2, 2)
            else -> PageLayout(2, 2) // 5+ → 2×2 paged
        }
    } else {
        when (count) {
            1 -> PageLayout(1, 1)
            2 -> PageLayout(2, 1)
            3 -> PageLayout(3, 1)
            4 -> PageLayout(2, 2)
            5, 6 -> PageLayout(3, 2)
            else -> PageLayout(3, 2) // 7+ → 3×2 paged
        }
    }
}

/**
 * Gallery mode: shows participants in a paged, adaptive grid.
 * Portrait: ≤4 bespoke layouts; 5+ → 2×2 pages.
 * Landscape: ≤6 bespoke; 7+ → 3×2 pages.
 */
@Composable
fun GalleryLayout(
    room: Room,
    participants: List<ParticipantUi>,
    focusIdentity: String?,
    showPinButtons: Boolean,
    onPin: (String) -> Unit,
    onStopScreenShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (participants.isEmpty()) return

    val isPortrait = LocalConfiguration.current.screenWidthDp <=
        LocalConfiguration.current.screenHeightDp
    val count = participants.size
    val layout = galleryPageLayout(isPortrait, count)
    val gap = Dimens.SpaceXs

    // Single-page bespoke layouts for small counts.
    if (isPortrait && count == 3) {
        // 1 big on top, 2 small on bottom row.
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            ParticipantTile(
                room = room,
                participant = participants[0],
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                showPinButton = showPinButtons,
                isPinned = participants[0].identity == focusIdentity,
                onPinClick = { onPin(participants[0].identity) },
                onStopScreenShare = onStopScreenShare,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                ParticipantTile(
                    room = room,
                    participant = participants[1],
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f),
                    showPinButton = showPinButtons,
                    isPinned = participants[1].identity == focusIdentity,
                    onPinClick = { onPin(participants[1].identity) },
                    onStopScreenShare = onStopScreenShare,
                )
                ParticipantTile(
                    room = room,
                    participant = participants[2],
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f),
                    showPinButton = showPinButtons,
                    isPinned = participants[2].identity == focusIdentity,
                    onPinClick = { onPin(participants[2].identity) },
                    onStopScreenShare = onStopScreenShare,
                )
            }
        }
        return
    }

    val perPage = layout.perPage
    val pageCount = (count + perPage - 1) / perPage
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Box(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { pageIndex ->
            val start = pageIndex * perPage
            val end = minOf(start + perPage, count)
            val pageItems = participants.subList(start, end)
            GridPage(
                room = room,
                participants = pageItems,
                cols = layout.cols,
                rows = layout.rows,
                gap = gap,
                focusIdentity = focusIdentity,
                showPinButtons = showPinButtons,
                onPin = onPin,
                onStopScreenShare = onStopScreenShare,
            )
        }

        if (pageCount > 1) {
            PageIndicator(
                pageCount = pageCount,
                currentPage = pagerState.currentPage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = Dimens.SpaceS),
            )
        }
    }
}

/** One page of the gallery: cols × rows equal-weight tiles, with blanks for
 *  short pages so the last page's tiles stay the same size as full pages. */
@Composable
private fun GridPage(
    room: Room,
    participants: List<ParticipantUi>,
    cols: Int,
    rows: Int,
    gap: androidx.compose.ui.unit.Dp,
    focusIdentity: String?,
    showPinButtons: Boolean,
    onPin: (String) -> Unit,
    onStopScreenShare: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        for (r in 0 until rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                for (c in 0 until cols) {
                    val idx = r * cols + c
                    if (idx < participants.size) {
                        val p = participants[idx]
                        ParticipantTile(
                            room = room,
                            participant = p,
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f),
                            showPinButton = showPinButtons,
                            isPinned = p.identity == focusIdentity,
                            onPinClick = { onPin(p.identity) },
                            onStopScreenShare = onStopScreenShare,
                        )
                    } else {
                        // Blank cell to keep the grid shape on the last page.
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** Small dots under the pager indicating current page. */
@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(Dimens.CornerS))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXs),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until pageCount) {
            Box(
                modifier = Modifier
                    .size(if (i == currentPage) Dimens.Room.PageDotActive else Dimens.Room.PageDotInactive)
                    .clip(CircleShape)
                    .background(
                        if (i == currentPage) Color.White
                        else Color.White.copy(alpha = 0.5f)
                    ),
            )
        }
    }
}

/**
 * Focus mode: one big tile plus a scrolling carousel of the other
 * participants. Orientation-aware: carousel sits at the bottom in portrait,
 * on the right in landscape.
 */
@Composable
fun FocusLayout(
    room: Room,
    participants: List<ParticipantUi>,
    focusIdentity: String,
    showPinButtons: Boolean,
    onPin: (String) -> Unit,
    onUnpin: () -> Unit,
    onStopScreenShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = participants.firstOrNull { it.identity == focusIdentity } ?: return
    val others = participants.filter { it.identity != focusIdentity }

    val isPortrait = LocalConfiguration.current.screenWidthDp <=
        LocalConfiguration.current.screenHeightDp

    if (isPortrait) {
        Column(modifier = modifier.fillMaxSize()) {
            ParticipantTile(
                room = room,
                participant = focus,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.65f),
                showPinButton = showPinButtons,
                isPinned = true,
                onPinClick = onUnpin,
                onStopScreenShare = onStopScreenShare,
            )
            if (others.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.Room.CarouselTileHeight)
                        .padding(top = Dimens.SpaceXs),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                ) {
                    items(others, key = { it.identity }) { p ->
                        ParticipantTile(
                            room = room,
                            participant = p,
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(Dimens.Room.CarouselTileWidth),
                            showPinButton = showPinButtons,
                            isPinned = false,
                            onPinClick = { onPin(p.identity) },
                            onStopScreenShare = onStopScreenShare,
                        )
                    }
                }
            }
        }
    } else {
        Row(modifier = modifier.fillMaxSize()) {
            ParticipantTile(
                room = room,
                participant = focus,
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.75f),
                showPinButton = showPinButtons,
                isPinned = true,
                onPinClick = onUnpin,
                onStopScreenShare = onStopScreenShare,
            )
            if (others.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(Dimens.Room.CarouselTileWidth)
                        .padding(start = Dimens.SpaceXs),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                ) {
                    items(others, key = { it.identity }) { p ->
                        ParticipantTile(
                            room = room,
                            participant = p,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(Dimens.Room.CarouselTileHeight),
                            showPinButton = showPinButtons,
                            isPinned = false,
                            onPinClick = { onPin(p.identity) },
                            onStopScreenShare = onStopScreenShare,
                        )
                    }
                }
            }
        }
    }
}
