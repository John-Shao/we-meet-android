package com.we.meet.data

import com.we.meet.data.api.UploadTranslationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.UploadTranslationRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class UploadTranslationRepositoryTest {
    private val record = UUID.randomUUID().toString()
    private val id = UUID.randomUUID().toString()
    private val row = UploadTranslationSegmentDto(UUID.randomUUID().toString(), 1200, "Speaker", "Original", "Translation")
    private val item = UploadTranslationDto(id, record, "en", "succeeded", 1, false, 51, 3, 3, listOf(row), 1)
    private var viewer: String? = "owner"
    private var returned = item
    private var reads = 0
    private val sent = mutableListOf<UploadTranslationRequestDto>()
    private val api = object : UploadTranslationApi {
        override suspend fun list(record: String): UploadTranslationListDto { reads++; return UploadTranslationListDto(true, 1, listOf(returned)) }
        override suspend fun detail(record: String, translation: String, page: Int): UploadTranslationDto { reads++; return returned }
        override suspend fun generate(record: String, body: UploadTranslationRequestDto): UploadTranslationDto { sent += body; return returned }
        override suspend fun export(record: String, translation: String, format: String) = "translated".toResponseBody()
    }
    private val repo = UploadTranslationRepository(api) { viewer }
    @Test fun validatesPinnedRecordPageAndFullText() = runBlocking {
        assertEquals(item, repo.detail("owner", record, id, 0).getOrThrow())
        for (bad in listOf(item.copy(id = record), item.copy(recordId = id), item.copy(nextPage = 0), item.copy(status = "queued"), item.copy(results = listOf(row, row)))) {
            returned = bad
            assertTrue(repo.detail("owner", record, id, 0).isFailure)
        }
    }
    @Test fun validatesLanguageAndRevisionOfAcceptedRequest() = runBlocking {
        val request = UploadTranslationRequestDto(UUID.randomUUID().toString(), "en", 1)
        assertTrue(repo.generate("owner", record, request).isSuccess)
        returned = item.copy(target = "zh")
        assertTrue(repo.generate("owner", record, request).isFailure)
        assertEquals(listOf(request, request), sent)
    }
    @Test fun accountMismatchPreventsReadsAndUnsupportedExportIsRejected() = runBlocking {
        viewer = "other"
        assertTrue(repo.list("owner", record).isFailure)
        assertEquals(0, reads)
        viewer = "owner"
        assertTrue(repo.export("owner", record, id, "html").isFailure)
        assertEquals("translated", repo.export("owner", record, id, "txt").getOrThrow().use { it.string() })
    }
}
