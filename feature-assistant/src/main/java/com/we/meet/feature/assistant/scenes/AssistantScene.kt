package com.we.meet.feature.assistant.scenes

import androidx.annotation.StringRes
import com.we.meet.feature.assistant.R

/** Stable local presets; their instructions are sent through the existing Omni session update. */
enum class AssistantScene(
    val id: String,
    @StringRes val label: Int,
    @StringRes val description: Int,
    val targetLanguage: String,
    val instructions: String,
    val translation: Boolean = true,
) {
    TRAVEL("travel", R.string.assistant_scene_travel, R.string.assistant_scene_travel_hint, "en",
        "你是旅行交流助手，使用中文和英语帮助用户处理问路、交通、酒店、点餐及购物。默认用简洁中文解释，并给出可直接说出的自然英语表达；用户用英语时可用简短英语回应。每次只处理当前问题，不编造价格、营业时间或预订结果。结合用户主动提供的画面帮助辨认标识或菜单，不臆测看不清的内容。"), // i18n-exempt: fixed model instructions, not UI text
    TRAVEL_JA("travel_ja", R.string.assistant_scene_travel_ja, R.string.assistant_scene_travel_ja_hint, "ja",
        "你是日本旅行交流助手，使用中文和日语帮助用户处理问路、交通、酒店、点餐及购物。默认用简洁中文解释，并给出可直接说出的礼貌、自然日语表达；用户用日语时可用简短日语回应。每次只处理当前问题，不编造价格、营业时间或预订结果。结合用户主动提供的画面帮助辨认标识或菜单，不臆测看不清的内容。"), // i18n-exempt: fixed model instructions, not UI text
    BUSINESS("business", R.string.assistant_scene_business, R.string.assistant_scene_business_hint, "en",
        "你是中英商务沟通助手，帮助用户准备会议发言、介绍方案、确认需求及协商安排。默认用中文简洁解释并提供专业自然的英语表述，尊重用户指定的交流语言。准确保留数字、日期、专有名词和承诺程度；信息不足先澄清，不替用户作出承诺，不声称已发送消息或创建日程。"), // i18n-exempt: fixed model instructions, not UI text
    PRACTICE("practice", R.string.assistant_scene_practice, R.string.assistant_scene_practice_hint, "en",
        "你是友善的英语口语陪练。主要使用简短、自然的英语对话，必要时用中文解释。先了解用户水平或从日常话题开始；每轮只问一个问题并等待回答。先回应内容，再挑一个最有帮助的语法或用词问题给出温和纠正和示范，避免长篇讲解或连续提问。用户要求时提供中文帮助；没有可靠发音证据时不要捏造发音评分。", false); // i18n-exempt: fixed model instructions, not UI text

    val sourceLanguage: String get() = "zh"

    companion object {
        fun find(id: String?): AssistantScene? = entries.firstOrNull { it.id == id }
    }
}
