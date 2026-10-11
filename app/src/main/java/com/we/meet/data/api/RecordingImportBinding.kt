package com.we.meet.data.api

import com.we.meet.data.auth.PrivateLogin
import com.we.meet.data.repository.IdentityLoginChangedException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MultipartBody
import okhttp3.RequestBody

/** Keep the existing upload interface testable; production requests always carry the captured login tag. */
internal class RecordingImportBinding(private val fallback: RecordingUploadApi, private val api: RecordingImportApi?,
    private val owner: String, private val login: PrivateLogin, private val active: () -> Boolean) : RecordingUploadApi {
    private suspend fun <T> request(old: suspend () -> T, bound: suspend (RecordingImportApi) -> T): T {
        currentCoroutineContext().ensureActive()
        if (!active()) throw IdentityLoginChangedException()
        val result = if (api == null) old() else bound(api)
        currentCoroutineContext().ensureActive()
        if (!active()) throw IdentityLoginChangedException()
        return result
    }
    override suspend fun capabilities() = request({ fallback.capabilities() }) { it.capabilities(login) }
    override suspend fun personalHotwords() = request({ fallback.personalHotwords() }) { it.personalHotwords(login) }
    override suspend fun savePersonalHotwords(body: PersonalHotwordsRequest) = request({ fallback.savePersonalHotwords(body) }) { it.savePersonalHotwords(login, body) }
    override suspend fun upload(key: RequestBody, audio: MultipartBody.Part, context: RequestBody, hotwords: RequestBody, diarization: RequestBody) =
        request({ fallback.upload(key, audio, context, hotwords, diarization) }) { it.upload(owner, login, key, audio, context, hotwords, diarization, null) }
    override suspend fun uploadIdentity(key: RequestBody, audio: MultipartBody.Part, context: RequestBody, hotwords: RequestBody, diarization: RequestBody, identity: RequestBody) =
        request({ fallback.uploadIdentity(key, audio, context, hotwords, diarization, identity) }) { it.upload(owner, login, key, audio, context, hotwords, diarization, identity) }
    override suspend fun presign(body: RecordingUploadPresign) = request({ fallback.presign(body) }) { it.presign(owner, login, body) }
    override suspend fun complete(body: RecordingUploadComplete) = request({ fallback.complete(body) }) { it.complete(owner, login, body) }
    override suspend fun state(recordId: String) = request({ fallback.state(recordId) }) { it.state(recordId, login) }
    override suspend fun retry(recordId: String, body: RecordingUploadRetry) = request({ fallback.retry(recordId, body) }) { it.retry(recordId, login, body) }
    override suspend fun multipartBegin(body: RecordingUploadBegin) = request({ fallback.multipartBegin(body) }) { it.multipartBegin(owner, login, body) }
    override suspend fun multipartResume(sessionId: String) = request({ fallback.multipartResume(sessionId) }) { it.multipartResume(sessionId, owner, login) }
    override suspend fun multipartSign(sessionId: String, body: RecordingUploadSign) = request({ fallback.multipartSign(sessionId, body) }) { it.multipartSign(sessionId, owner, login, body) }
    override suspend fun multipartComplete(sessionId: String, body: RecordingUploadFinish) = request({ fallback.multipartComplete(sessionId, body) }) { it.multipartComplete(sessionId, owner, login, body) }
    override suspend fun multipartAbort(sessionId: String) = request({ fallback.multipartAbort(sessionId) }) { it.multipartAbort(sessionId, owner, login) }
}
