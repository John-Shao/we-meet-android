package com.we.meet.feature.assistant.aicall.model

/** Immutable video targets selected before connecting, shared by both transports. */
data class AiCallVideoSettings(
    val localPreviewFps: Int = 15,
    val modelUploadFps: Int = 2,
) {
    init {
        require(localPreviewFps in 10..30)
        require(modelUploadFps in 1..10)
    }

    // Camera2 may need a higher hardware cadence than the desired preview rate.
    val captureFps: Int get() = maxOf(15, localPreviewFps)

    fun withLocalPreviewFps(value: Int): AiCallVideoSettings =
        copy(localPreviewFps = value)

    companion object {
        fun fromStored(preview: Int, upload: Int): AiCallVideoSettings {
            val validPreview = preview.takeIf { it in 10..30 } ?: 15
            val validUpload = upload.takeIf { it in 1..10 } ?: 2
            return AiCallVideoSettings(validPreview, validUpload)
        }
    }
}
