package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.VoiceprintFixtures as F
import com.we.meet.data.api.*
import com.we.meet.data.auth.PrivateLogin
import com.we.meet.data.auth.requirePrivateLogin
import com.we.meet.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.CopyOnWriteArrayList

class RecordingImportContractTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val intent = RecordingImportIdentity(null, listOf(F.OWNER))
    private val config = RecordingUploadCapabilities(true, 512 * 1024, listOf("wav"), true, 512L * 1024 * 1024,
        identityPreflight = RecordingIdentityCapability(true, maxCandidates = 2, maxBytes = 512L * 1024 * 1024, maxDurationMs = 7_200_000))
    private val queued = RecordingUploadState(F.PROFILE, "queued", 1)
    private val ticket = RecordingUploadTicket("https://storage.invalid/fixture", "private-fixture.wav", mapOf("Content-Type" to "audio/wav"))
    private inner class Fixture {
        @Volatile var viewer: String? = F.OWNER
        @Volatile var session = "synthetic-login"
        val requests = CopyOnWriteArrayList<Request>()
        val bodies = CopyOnWriteArrayList<String>()
        var puts = 0
        var partPuts = 0
        var reply: (Request) -> Pair<Int, Any> = { 200 to queued }
        val retrofit = Retrofit.Builder().baseUrl("https://fixture.invalid/").validateEagerly(true)
            .client(OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                val request = chain.request(); requirePrivateLogin(request, session)
                requests += request
                bodies += request.body?.let { Buffer().also(it::writeTo).readUtf8() } ?: ""
                val (code, value) = reply(request)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Synthetic").code(code)
                    .body(moshi.adapter(Any::class.java).serializeNulls().toJson(value).toResponseBody("application/json".toMediaType())).build()
            }.build()).addConverterFactory(MoshiConverterFactory.create(moshi).withNullSerialization()).build()
        val raw = retrofit.create(RecordingUploadApi::class.java)
        val storage = RecordingStorage { _, headers, size, open ->
            assertFalse(headers.keys.any { it.equals("Authorization", true) || it.equals("X-Voiceprint-Owner", true) })
            assertEquals(size, open().use { it.readBytes().size.toLong() }); puts++; true
        }
        val parts = RecordingPartStorage { _, size, open, progress, cancel ->
            check(!cancel()); assertEquals(size, open().use { it.readBytes().size.toLong() }); partPuts++; progress(size); "synthetic-etag"
        }
        val root = RecordingUploadRepository(raw, { viewer }, storage, parts, retrofit.create(RecordingImportApi::class.java), { session })
        val client = root.open(F.OWNER)
        fun body(index: Int = bodies.lastIndex): Map<*, *> = requireNotNull(moshi.adapter(Map::class.java).fromJson(bodies[index]))
        fun bound() { requests.forEach { assertEquals("synthetic-login", it.tag(PrivateLogin::class.java)?.session); assertNull(it.header("X-Private-Login")) } }
    }
    private suspend fun direct(f: Fixture, held: RecordingUploadTicket? = null, limits: RecordingUploadCapabilities = config,
        value: RecordingImportIdentity = intent, onTicket: (RecordingUploadTicket) -> Unit = {}) =
        f.client.uploadDirect(F.OWNER, F.KEY, "Synthetic.wav", 9, limits, "", "", { ByteArray(9).inputStream() },
            held, "audio/wav", onTicket = onTicket, diarization = true, identity = value)

    @Test fun legacyUploadCarriesExplicitPersonalNullAndOwnerWithoutImplicitCandidates() = runBlocking {
        val f = Fixture()
        assertTrue(f.client.uploadWithProgress(F.OWNER, F.KEY, "Synthetic.wav", 9, config, "", "", { ByteArray(9).inputStream() },
            diarization = true, identity = intent) { _, _ -> }.isSuccess)
        assertTrue(f.bodies.single().contains("\"organization_id\":null"))
        assertTrue(f.bodies.single().contains("\"candidate_user_ids\":[\"${F.OWNER}\"]"))
        assertEquals(F.OWNER, f.requests.single().header("X-Voiceprint-Owner")); f.bound()
    }
    @Test fun directUploadCopiesIdentityBeforeSuspendingAndAdoptsIdenticalIntent() = runBlocking {
        val f = Fixture(); val ids = mutableListOf(F.OWNER)
        f.reply = { if (it.url.encodedPath.endsWith("upload-url/")) { ids.clear(); 200 to ticket } else 200 to queued }
        assertTrue(direct(f, value = RecordingImportIdentity(null, ids)).isSuccess)
        assertEquals(f.body(0)["identity"], f.body(1)["identity"])
        assertEquals(listOf(F.OWNER), (f.body(1)["identity"] as Map<*, *>)["candidate_user_ids"])
        assertEquals(1, f.puts); f.requests.forEach { assertEquals(F.OWNER, it.header("X-Voiceprint-Owner")) }; f.bound()
    }
    @Test fun storedBytesCanBeConfirmedAfterMatchingIsDisabledWithoutAnotherPut() = runBlocking {
        val f = Fixture(); var completions = 0; var held: RecordingUploadTicket? = null
        f.reply = { if (it.url.encodedPath.endsWith("upload-url/")) 200 to ticket else if (completions++ == 0) 503 to mapOf("code" to "unavailable") else 200 to queued }
        assertTrue(direct(f, onTicket = { held = it }).isFailure); assertTrue(held!!.uploaded)
        assertTrue(direct(f, held, config.copy(identityPreflight = RecordingIdentityCapability(false))).isSuccess)
        assertEquals(1, f.puts); assertEquals(f.bodies[1], f.bodies[2])
    }
    @Test fun chunkedBeginUsesTheSameIntentAndEveryControlRequestIsBound() = runBlocking {
        val f = Fixture()
        val plan = RecordingUploadPlan(F.PROFILE, 9, 4, 3)
        f.reply = { request -> 200 to when {
            request.url.encodedPath.endsWith("begin/") -> plan
            request.url.encodedPath.endsWith("parts/") -> plan.copy(parts = (1..3).map { RecordingUploadPartPlan(it, "https://storage.invalid/$it", if (it == 3) 1 else 4) })
            else -> queued
        } }
        assertTrue(f.client.uploadChunked(ChunkedUploadRequest(F.OWNER, F.KEY, "Synthetic.wav", 9, config, "", "", "audio/wav",
            openAt = { _, length -> ByteArray(length.toInt()).inputStream() }, onProgress = { _, _ -> }, cancelled = { false }, diarization = true, identity = intent)).isSuccess)
        assertEquals(listOf(F.OWNER), (f.body(0)["identity"] as Map<*, *>)["candidate_user_ids"])
        assertEquals(3, f.partPuts); f.bound(); f.requests.forEach { assertEquals(F.OWNER, it.header("X-Voiceprint-Owner")) }
    }
    @Test fun candidatesAreScopedNamesOnlyAndRequireTheOriginalLogin() = runBlocking {
        val f = Fixture(); f.reply = { 200 to mapOf("organization_id" to null, "results" to listOf(mapOf("id" to F.OWNER, "name" to "Synthetic")), "next_offset" to null) }
        assertEquals("Synthetic", f.client.importCandidates(F.OWNER, null, "Sample", 0).getOrThrow().results.single().name)
        val request = f.requests.single()
        assertEquals("personal", request.url.queryParameter("organization_id")); assertEquals("Sample", request.url.queryParameter("q"))
        assertEquals(F.OWNER, request.header("X-Voiceprint-Owner")); f.bound()
        f.session = "different-login"; assertTrue(f.client.importCandidates(F.OWNER, null).isFailure); assertEquals(1, f.requests.size)
    }
    @Test fun malformedOrForeignCandidatePagesAreRejected() = runBlocking {
        val f = Fixture()
        for (reply in listOf(
            mapOf("organization_id" to F.ORGANIZATION, "results" to emptyList<Any>(), "next_offset" to null),
            mapOf("organization_id" to null, "results" to listOf(mapOf("id" to F.PROFILE, "name" to "Other")), "next_offset" to null),
            mapOf("organization_id" to null, "results" to listOf(mapOf("id" to F.OWNER, "name" to "Synthetic"), mapOf("id" to F.OWNER, "name" to "Duplicate")), "next_offset" to null),
            mapOf("organization_id" to null, "results" to emptyList<Any>(), "next_offset" to 0),
        )) { f.reply = { 200 to reply }; assertTrue(f.client.importCandidates(F.OWNER, null).isFailure) }
    }
    @Test fun explicitContinueUsesTheOriginalAttemptAndDoesNotReupload() = runBlocking {
        val f = Fixture(); f.reply = { 202 to queued.copy(attempt = 2, identityPreflight = RecordingIdentityPreflight("disabled")) }
        assertTrue(f.client.decidePreflight(F.OWNER, F.PROFILE, 1, "continue_without_identity").isSuccess)
        assertEquals(1.0, f.body()["expected_attempt"]); assertEquals("continue_without_identity", f.body()["action"])
        assertEquals(0, f.puts); assertEquals(F.OWNER, f.requests.single().header("X-Voiceprint-Owner")); f.bound()
    }
    @Test fun invalidDecisionAcknowledgementsAndConflictsDoNotRetryAutomatically() = runBlocking {
        val f = Fixture(); f.reply = { 202 to queued.copy(attempt = 3, identityPreflight = RecordingIdentityPreflight("disabled")) }
        assertTrue(f.client.decidePreflight(F.OWNER, F.PROFILE, 1, "continue_without_identity").isFailure)
        f.reply = { 409 to mapOf("code" to "transcription_conflict") }
        assertTrue(f.client.decidePreflight(F.OWNER, F.PROFILE, 1, "retry_identity").isFailure)
        assertEquals(2, f.requests.size); assertEquals(0, f.puts)
    }
    @Test fun aLatePresignAfterSameAccountReloginNeverUploadsOrReturnsATicket() = runBlocking {
        val f = Fixture(); f.reply = { f.session = "different-login"; 200 to ticket }
        var callback = false
        assertTrue(direct(f, onTicket = { callback = true }).isFailure)
        assertFalse(callback); assertEquals(0, f.puts); assertTrue(direct(f).isFailure); assertEquals(1, f.requests.size)
    }
    @Test fun ordinaryRequestsAlsoRemainBoundAcrossSameAccountRelogin() = runBlocking {
        val f = Fixture(); f.reply = { 200 to config }
        assertTrue(f.client.capabilities(F.OWNER).isSuccess)
        f.session = "different-login"; assertTrue(f.client.capabilities(F.OWNER).isFailure)
        val fresh = f.root.open(F.OWNER); assertTrue(fresh.capabilities(F.OWNER).isSuccess)
        assertEquals("different-login", f.requests.last().tag(PrivateLogin::class.java)?.session)
        assertEquals(2, f.requests.size)
    }
    @Test fun unavailableCapabilitiesAndUnscopedIntentAreRejectedBeforeSendingBytes() = runBlocking {
        val f = Fixture()
        assertTrue(direct(f, limits = config.copy(identityPreflight = RecordingIdentityCapability(false))).isFailure)
        assertTrue(direct(f, value = RecordingImportIdentity(null, listOf(F.PROFILE))).isFailure)
        assertTrue(direct(f, value = intent.copy(candidateUserIds = listOf(F.OWNER, F.OWNER))).isFailure)
        assertTrue(f.requests.isEmpty()); assertEquals(0, f.puts)
    }
    @Test fun aCancelledResumeCannotFallBackToBeginningAnotherUpload() = runBlocking {
        val f = Fixture(); var began = false
        val api = object : RecordingUploadApi by f.raw {
            override suspend fun multipartResume(sessionId: String): RecordingUploadPlan = throw CancellationException()
            override suspend fun multipartBegin(body: RecordingUploadBegin): RecordingUploadPlan { began = true; return RecordingUploadPlan() }
        }
        val client = RecordingUploadRepository(api, { F.OWNER }, partStorage = f.parts)
        try { client.uploadChunked(ChunkedUploadRequest(F.OWNER, F.KEY, "Synthetic.wav", 9, config, "", "", "audio/wav", F.PROFILE,
            openAt = { _, length -> ByteArray(length.toInt()).inputStream() }, onProgress = { _, _ -> }, cancelled = { false })); fail("Cancellation must propagate") }
        catch (_: CancellationException) { assertFalse(began) }
    }
    @Test fun unknownLengthIdentityImportsRemainStreamedAndBounded() = runBlocking {
        val f = Fixture()
        val bounded = config.copy(identityPreflight = config.identityPreflight!!.copy(maxBytes = 8))
        val accepted = f.client.uploadWithProgress(F.OWNER, F.KEY, "Synthetic.wav", null, bounded, "", "", { ByteArray(8).inputStream() },
            diarization = true, identity = intent) { _, _ -> }
        assertTrue(accepted.isSuccess)
        val rejected = f.client.uploadWithProgress(F.OWNER, F.KEY, "Synthetic.wav", null, bounded, "", "", { ByteArray(9).inputStream() },
            diarization = true, identity = intent) { _, _ -> }
        assertTrue(rejected.isFailure); assertEquals(0, f.puts)
    }
    @Test fun anOuterTimeoutRemainsCancellationInsteadOfAPrivateRequestFailure() = runBlocking {
        val f = Fixture()
        val network = object : RecordingImportApi by f.retrofit.create(RecordingImportApi::class.java) {
            override suspend fun scopes(owner: String, login: PrivateLogin, offset: Int): com.we.meet.data.api.dto.VoiceprintPageDto<com.we.meet.data.api.dto.VoiceprintScopeDto> {
                delay(10_000); error("Must be cancelled")
            }
        }
        val client = RecordingUploadRepository(f.raw, { F.OWNER }, importApi = network).open(F.OWNER)
        try { withTimeout(50) { client.importScopes(F.OWNER) }; fail("Outer cancellation must propagate") }
        catch (_: CancellationException) { assertTrue(f.requests.isEmpty()) }
    }
}
