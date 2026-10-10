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

/** The model supplies the question it understood from this turn's speech. */
fun interface PhotoToolHandler {
    suspend fun execute(question: String): CameraActionResult
}

data class CameraPermissionRequest(val id: String)

/** Camera teardown could not be confirmed; the call owner must release all media. */
internal class PhotoCleanupException(cause: Throwable) : IllegalStateException("Photo camera cleanup failed", cause)

enum class CameraFeedbackStage { StateSync, ToolExecution, ResultSend, ResponseEnd, Continuation }

/** The result belongs to this failed tool round; null never means the previous result. */
data class CameraFeedbackFailure(val stage: CameraFeedbackStage, val result: CameraActionResult?)
