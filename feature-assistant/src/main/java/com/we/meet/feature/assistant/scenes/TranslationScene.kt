package com.we.meet.feature.assistant.scenes

import androidx.annotation.StringRes
import com.we.meet.feature.assistant.R

/** Local scenario preference, independent of translation languages and playback. */
enum class TranslationScene(val id: String, @StringRes val label: Int) {
    TRAVEL("travel", R.string.assistant_translation_scene_travel),
    BUSINESS("business", R.string.assistant_scene_business);

    companion object {
        fun find(id: String?): TranslationScene? =
            entries.firstOrNull { it.id == if (id == "travel_ja") "travel" else id }
    }
}
