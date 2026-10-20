package com.we.meet.feature.assistant.screenread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSnapshotBoundsTest {
    @Test fun activeWindowClipsToWindow() {
        val bounds = ScreenSnapshotBounds.activeWindow(
            200, 300, ScreenSnapshotBounds.Bounds(10, 20, 110, 220),
        )
        assertEquals(ScreenSnapshotBounds.Bounds(10, 20, 110, 220), bounds)
    }

    @Test fun invalidWindowFallsBackToFullBitmap() {
        val bounds = ScreenSnapshotBounds.activeWindow(
            200, 300, ScreenSnapshotBounds.Bounds(0, 0, 0, 0),
        )
        assertEquals(ScreenSnapshotBounds.Bounds(0, 0, 200, 300), bounds)
    }

    @Test fun trimsStatusBarAndTrailingWhiteRows() {
        val width = 40
        val bounds = ScreenSnapshotBounds.content(width, 100, 30, cropStatusBar = true) { y ->
            when (y) {
                80 -> IntArray(width) { 0xFF000000.toInt() }
                else -> IntArray(width) { 0xFFFFFFFF.toInt() }
            }
        }
        assertEquals(30, bounds.top)
        assertEquals(100, bounds.bottom)
        assertTrue(bounds.height < 100)
    }

    @Test fun keepsTopWhenStatusBarAlreadyExcluded() {
        val bounds = ScreenSnapshotBounds.content(40, 100, 30, cropStatusBar = false) { y ->
            when (y) {
                80 -> IntArray(40) { 0xFF000000.toInt() }
                else -> IntArray(40) { 0xFFFFFFFF.toInt() }
            }
        }
        assertEquals(0, bounds.top)
        assertEquals(100, bounds.bottom)
    }

    @Test fun keepsFullBitmapWhenNoContentIsFound() {
        val bounds = ScreenSnapshotBounds.content(40, 100, 30, cropStatusBar = true) {
            IntArray(40) { 0xFFFFFFFF.toInt() }
        }
        assertEquals(ScreenSnapshotBounds.Bounds(0, 0, 40, 100), bounds)
    }

    @Test fun treatsLightGrayPageBackgroundAsBlank() {
        val bounds = ScreenSnapshotBounds.content(40, 100, 0, cropStatusBar = false) { y ->
            if (y == 40) IntArray(40) { 0xFF202020.toInt() }
            else IntArray(40) { 0xFFF2F2F2.toInt() }
        }
        assertEquals(64, bounds.bottom)
    }

    @Test fun ignoresDarkDisplayBorderAtTheBottom() {
        val bounds = ScreenSnapshotBounds.content(400, 1280, 0, cropStatusBar = false) { y ->
            when {
                y >= 1270 -> IntArray(400) { 0xFF101010.toInt() }
                y == 800 -> IntArray(400) { 0xFF202020.toInt() }
                else -> IntArray(400) { 0xFFF2F2F2.toInt() }
            }
        }
        assertEquals(824, bounds.bottom)
        assertEquals(0, bounds.left)
        assertEquals(400, bounds.right)
    }
}
