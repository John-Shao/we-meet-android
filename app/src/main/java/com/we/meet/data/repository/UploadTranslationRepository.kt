package com.we.meet.data.repository

import com.we.meet.data.api.UploadTranslationApi
import com.we.meet.data.api.dto.*
import kotlinx.coroutines.CancellationException
import okhttp3.ResponseBody

class UploadTranslationRepository(private val api: UploadTranslationApi, private val currentViewer: () -> String?) {
    suspend fun list(viewer: String, record: String) = scoped(viewer, record) {
        api.list(record).also { page ->
            require(page.revision > 0 && page.results.size <= 30)
            require(page.results.map { it.id }.distinct().size == page.results.size)
            page.results.forEach { validate(it, record) }
        }
    }
    suspend fun generate(viewer: String, record: String, request: UploadTranslationRequestDto) = scoped(viewer, record) {
        MeetingTranslationRepository.uuid(request.key)
        require(request.target in setOf("zh", "en") && request.expectedRevision > 0)
        api.generate(record, request).also {
            validate(it, record); require(it.target == request.target && it.inputRevision == request.expectedRevision)
        }
    }
    suspend fun detail(viewer: String, record: String, translation: String, page: Int) = scoped(viewer, record) {
        MeetingTranslationRepository.uuid(translation); require(page in 0..40)
        api.detail(record, translation, page).also {
            validate(it, record); require(it.id == translation && it.results.size <= 50)
            require(it.nextPage == null || it.nextPage == page + 1 && it.nextPage <= 40)
            require(it.results.map { row -> row.segmentId }.distinct().size == it.results.size)
            it.results.forEach { row ->
                MeetingTranslationRepository.uuid(row.segmentId)
                require(row.startMs >= 0 && row.text.isNotBlank() && row.translatedText.isNotBlank())
                require(row.text.length <= 6000 && row.translatedText.length <= 24000)
            }
        }
    }
    suspend fun export(viewer: String, record: String, translation: String, format: String) = scoped(viewer, record) {
        MeetingTranslationRepository.uuid(translation); require(format in setOf("txt", "srt", "vtt"))
        api.export(record, translation, format)
    }
    private suspend fun <T> scoped(viewer: String, record: String, action: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer); MeetingTranslationRepository.uuid(record)
        val value = action()
        if (currentViewer() != viewer) {
            if (value is ResponseBody) value.close()
            error("Account changed")
        }
        Result.success(value)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }
    private fun validate(value: UploadTranslationDto, record: String) {
        MeetingTranslationRepository.uuid(value.id)
        require(value.recordId == record && value.target in setOf("zh", "en") && value.inputRevision > 0)
        require(value.status in setOf("queued", "running", "succeeded", "failed", "incomplete", "canceled"))
        require(value.segmentCount in 1..2000 && value.totalChunks in 1..32 && value.completedChunks in 0..value.totalChunks)
        require(value.status == "succeeded" || value.results.isEmpty())
    }
}
