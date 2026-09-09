package com.we.meet.feature.docs.renderer

/** Supports both legacy inline arrays and BlockNote 0.51 tableCell objects. */
internal fun normalizeTableContent(raw: Any?): Any? {
    val table = raw as? Map<*, *> ?: return raw
    val rows = table["rows"] as? List<*> ?: return raw
    return table + ("rows" to rows.map { row ->
        val fields = row as? Map<*, *> ?: error("Invalid table row")
        val cells = fields["cells"] as? List<*> ?: error("Invalid table cells")
        fields + ("cells" to cells.map { cell ->
            if (cell is List<*>) cell else {
                val data = cell as? Map<*, *> ?: error("Invalid table cell")
                val props = data["props"] as? Map<*, *>
                // Merged cells require a grid renderer. Preserve the Web fallback instead of losing structure.
                require(listOf("colspan", "rowspan").all { ((props?.get(it) as? Number)?.toInt() ?: 1) == 1 })
                data["content"] as? List<*> ?: error("Invalid table cell content")
            }
        })
    })
}
