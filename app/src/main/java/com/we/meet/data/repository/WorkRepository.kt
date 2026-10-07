package com.we.meet.data.repository

import com.we.meet.data.api.WorkApi
import com.we.meet.data.api.WorkFileDto
import com.we.meet.data.api.RemoteWorkRequest
import com.we.meet.data.api.WorkReviewDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

class WorkRepository(private val api: WorkApi, private val currentSession: () -> String? = { "isolated-work-session" }) {
    val session: String? = currentSession()
    fun active() = session != null && currentSession() == session
    private suspend fun <T> scoped(action: suspend () -> T): T {
        check(active()) { "work_account_changed" }
        val result = action()
        check(active()) { "work_account_changed" }
        return result
    }
    suspend fun capabilities() = scoped { api.capabilities() }
    suspend fun workspaces() = scoped { api.workspaces().also { require(it.contract == "work-device/v1") }.workspaces }
    suspend fun tasks(page: Int) = scoped { api.tasks(page) }
    suspend fun task(id: String) = scoped { api.task(id).also { require(it.id == id) } }
    suspend fun files(id: String) = scoped { api.files(id) }
    suspend fun cancel(id: String) = scoped { api.cancel(id) }
    suspend fun dispatch(request: RemoteWorkRequest) = scoped {
        api.dispatch(request).also { require(it.contract == "work-device/v1" && it.run.id == request.runId && it.run.executionTarget == "local") }
    }
    suspend fun reviews(id: String) = scoped {
        api.reviews(id).also { rows ->
            require(rows.size <= 5 && rows.map { it.id }.distinct().size == rows.size)
            rows.forEach { review -> validateReview(id, review) }
        }
    }
    suspend fun preview(runId: String, file: WorkFileDto): String = scoped { withContext(Dispatchers.IO) {
        api.download(runId, file.name).use { response ->
            response.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val block = ByteArray(8192)
                while (true) {
                    val size = input.read(block)
                    if (size < 0) break
                    require(out.size() + size <= 400000) { "work_file_too_large" }
                    out.write(block, 0, size)
                }
                val bytes = out.toByteArray()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                require(digest == file.sha256) { "work_file_changed" }
                bytes.toString(Charsets.UTF_8)
            }
        }
    } }

    private fun validateReview(runId: String, review: WorkReviewDto) {
        require(UUID.fromString(review.id).toString() == review.id && review.sourceRunId == runId)
        fun sized(text: String, limit: Int) = text.codePointCount(0, text.length) <= limit
        fun files(files: List<WorkFileDto>, limit: Int) {
            require(files.size <= limit && files.map { it.name }.distinct().size == files.size)
            files.forEach { require(it.name.isNotBlank() && sized(it.name, 120) && it.sha256.matches(Regex("[a-f0-9]{64}"))) }
        }
        require(review.selection.isNotEmpty())
        files(review.selection, 8); files(review.snapshot, 20)
        require(review.model.isNotBlank() && sized(review.model, 80) && sized(review.status, 60) && sized(review.errorCode, 60))
        require(review.reservedTokens >= 0 && (review.inputTokens == null) == (review.outputTokens == null))
        require((review.inputTokens ?: 0) >= 0 && (review.outputTokens ?: 0) >= 0)
        val report = review.report
        require(sized(report.summary, 4000) && report.findings.size <= 20 && report.missingInformation.size <= 20)
        require(report.verdict == null || sized(report.verdict, 60))
        report.missingInformation.forEach { require(sized(it, 1000)) }
        report.findings.forEach { finding ->
            require(sized(finding.message, 2000) && finding.severity in setOf("error", "warning") && finding.evidence.size in 1..5)
            finding.evidence.forEach { ref ->
                require(ref.quote.isNotBlank() && sized(ref.quote, 500) && review.snapshot.any { it.name == ref.file && it.sha256 == ref.sha256 })
            }
        }
    }
}
