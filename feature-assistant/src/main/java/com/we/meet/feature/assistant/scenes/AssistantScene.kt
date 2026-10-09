package com.we.meet.feature.assistant.scenes

import androidx.annotation.StringRes
import com.we.meet.feature.assistant.R

/** Legacy scene identifiers and localized UI metadata; instructions are managed by the backend. */
enum class AssistantScene(
    val id: String,
    @StringRes val label: Int,
    @StringRes val description: Int,
    val targetLanguage: String,
    val translation: Boolean = true,
) {
    TRAVEL("travel", R.string.assistant_scene_travel, R.string.assistant_scene_travel_hint, "en"),
    TRAVEL_JA("travel_ja", R.string.assistant_scene_travel_ja, R.string.assistant_scene_travel_ja_hint, "ja"),
    BUSINESS("business", R.string.assistant_scene_business, R.string.assistant_scene_business_hint, "en"),
    PRACTICE("practice", R.string.assistant_scene_practice, R.string.assistant_scene_practice_hint, "en", false);

    val sourceLanguage: String get() = "zh"

    companion object {
        fun find(id: String?): AssistantScene? = entries.firstOrNull { it.id == id }
    }
}
