package com.we.meet.feature.assistant.aicall.rtc

import com.we.meet.feature.assistant.aicall.model.AiCallVadMode
import org.json.JSONObject

/** Change only the detector for comparisons; keep the existing threshold and silence window. */
internal fun AiCallVadMode.turnDetection(): JSONObject = JSONObject()
    .put("type", wireValue)
    .put("threshold", 0.5)
    .put("silence_duration_ms", 800)
