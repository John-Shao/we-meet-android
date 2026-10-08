package com.we.meet.feature.assistant.aicall.model

import org.json.JSONObject

enum class CameraActionSource { Button, Voice }

sealed interface CameraToolRequest {
    data class SetEnabled(val enabled: Boolean) : CameraToolRequest
    data object GetState : CameraToolRequest
}

data class CameraActionResult(
    val success: Boolean,
    val enabled: Boolean?,
    val changed: Boolean,
    val code: String,
    val message: String,
) {
    fun json(): String = JSONObject().put("success", success)
        .put("enabled", enabled ?: JSONObject.NULL).put("changed", changed)
        .put("code", code).put("message", message).toString()
}

fun interface CameraToolHandler {
    suspend fun execute(request: CameraToolRequest): CameraActionResult
}

data class CameraPermissionRequest(val id: String)
