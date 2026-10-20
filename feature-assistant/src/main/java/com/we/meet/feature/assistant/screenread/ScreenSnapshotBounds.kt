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
        var lastContent = top
        for (y in height - 1 downTo top) {
            val row = rowAt(y) ?: continue
            if (hasContent(row, width)) {
                lastContent = y
                break
            }
        }
        val bottom = (lastContent + 24).coerceAtMost(height)
        if (bottom - top < 40) return full
        return Bounds(0, top, width, bottom)
    }

    private fun hasContent(row: IntArray, width: Int): Boolean {
        var x = 0
        while (x < width && x < row.size) {
            val color = row[x]
            if (color shr 16 and 0xFF < 245 ||
                color shr 8 and 0xFF < 245 ||
                color and 0xFF < 245
            ) return true
            x += 4
        }
        return false
    }
}
