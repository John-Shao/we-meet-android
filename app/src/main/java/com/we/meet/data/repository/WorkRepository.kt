package com.we.meet.data.repository

import com.we.meet.data.api.WorkApi
import com.we.meet.data.api.WorkFileDto
import com.we.meet.data.api.RemoteWorkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class WorkRepository(private val api: WorkApi) {
    suspend fun capabilities() = api.capabilities()
    suspend fun workspaces() = api.workspaces().also { require(it.contract == "work-device/v1") }.workspaces
    suspend fun tasks(page: Int) = api.tasks(page)
    suspend fun task(id: String) = api.task(id)
    suspend fun files(id: String) = api.files(id)
    suspend fun cancel(id: String) = api.cancel(id)
    suspend fun dispatch(request: RemoteWorkRequest) = api.dispatch(request).also {
        require(it.contract == "work-device/v1" && it.run.id == request.runId && it.run.executionTarget == "local")
    }
    suspend fun preview(runId: String, file: WorkFileDto): String = withContext(Dispatchers.IO) {
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
    }
}
