package com.we.meet.feature.assistant.screenread

/** Pure bounds math for accessibility screenshots; unit-testable without Android graphics. */
internal object ScreenSnapshotBounds {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        fun isFullOf(bitmapWidth: Int, bitmapHeight: Int): Boolean =
            left == 0 && top == 0 && right == bitmapWidth && bottom == bitmapHeight
    }

    fun activeWindow(bitmapWidth: Int, bitmapHeight: Int, window: Bounds?): Bounds {
        val full = Bounds(0, 0, bitmapWidth, bitmapHeight)
        if (window == null || bitmapWidth < 2 || bitmapHeight < 2) return full
        val left = window.left.coerceIn(0, bitmapWidth - 1)
        val top = window.top.coerceIn(0, bitmapHeight - 1)
        val right = window.right.coerceIn(left + 1, bitmapWidth)
        val bottom = window.bottom.coerceIn(top + 1, bitmapHeight)
        return if (right - left >= 2 && bottom - top >= 2) Bounds(left, top, right, bottom) else full
    }

    /**
     * Keep the status bar out and trim the unused white area below a short page.
     * [rowAt] must return one row of ARGB pixels; returning null skips that row.
     */
    fun content(
        width: Int,
        height: Int,
        statusBarHeight: Int,
        cropStatusBar: Boolean,
        rowAt: (Int) -> IntArray?,
    ): Bounds {
        val full = Bounds(0, 0, width, height)
        if (width < 2 || height < 2) return full
        val top = if (cropStatusBar) {
            statusBarHeight.coerceIn(0, (height - 2).coerceAtLeast(0))
        } else 0
        if (height - top < 32) return full
        // Display corners and edge-to-edge chrome leave a dark border around
        // the capture; ignore it when looking for page content.
        val edge = maxOf(4, width / 50, height / 50)
        val left = edge.coerceAtMost(width / 2)
        val right = (width - edge).coerceAtLeast(left + 1)
        var lastContent = top
        for (y in (height - 1 - edge) downTo top) {
            val row = rowAt(y) ?: continue
            if (hasContent(row, left, right)) {
                lastContent = y
                break
            }
        }
        val bottom = (lastContent + 24).coerceAtMost(height)
        if (bottom - top < 40) return full
        // Keep full width: page text starts close to the display edge, and
        // cropping the edge inset would remove leading characters.
        return Bounds(0, top, width, bottom)
    }

    private fun hasContent(row: IntArray, left: Int, right: Int): Boolean {
        var x = left
        while (x < right && x < row.size) {
            val color = row[x]
            // Chrome's empty page background is light gray (not pure white), so
            // a near-white threshold would keep every blank row.
            if (color shr 16 and 0xFF < 200 ||
                color shr 8 and 0xFF < 200 ||
                color and 0xFF < 200
            ) return true
            x += 4
        }
        return false
    }
}
