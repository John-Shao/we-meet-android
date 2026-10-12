package com.we.meet.ui.main

import android.content.SharedPreferences

/** Ordered favorites are local to each account. Legacy sets retain their previous display order. */
internal class AppFavoritesStore(private val preferences: SharedPreferences, private val viewer: String) {
    private val orderKey = "pinned-order:$viewer"
    private val legacyKey = "pinned:$viewer"

    fun read(): List<String> {
        if (preferences.contains(orderKey)) {
            return normalize(preferences.getString(orderKey, "").orEmpty().split(','))
        }
        val legacy = preferences.getStringSet(legacyKey, setOf("calendar", "tasks", "approval")).orEmpty()
        val expanded = normalize(legacy.toList())
        val ordered = catalogOrder.filter { it in expanded }
        save(ordered)
        return ordered
    }

    fun save(ids: List<String>) {
        preferences.edit().putString(orderKey, normalize(ids).joinToString(","))
            .remove(legacyKey).apply()
    }

    private fun normalize(ids: List<String>): List<String> = ids
        .flatMap { if (it == "ai") listOf("ai-call", "bilingual") else listOf(it) }
        .filter { it in catalogOrder }.distinct()

    private companion object {
        val catalogOrder = listOf("calendar", "tasks", "contacts", "approval", "ai-call", "bilingual")
    }
}
