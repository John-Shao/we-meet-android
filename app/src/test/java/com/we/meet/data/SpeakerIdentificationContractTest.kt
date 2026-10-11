package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.IdentificationFixtures.OWNER
import com.we.meet.data.IdentificationFixtures.RECORD
import com.we.meet.data.IdentificationFixtures.SPEAKER
import com.we.meet.data.IdentificationFixtures.ORG
import com.we.meet.data.IdentificationFixtures.KEY
import com.we.meet.data.IdentificationFixtures.SUGGESTION
import com.we.meet.data.IdentificationFixtures.options
import com.we.meet.data.IdentificationFixtures.response
import com.we.meet.data.IdentificationFixtures.suggestion
import com.we.meet.data.IdentificationFixtures.submission
import com.we.meet.data.api.SpeakerIdentificationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.auth.PrivateLogin
import com.we.meet.data.auth.requirePrivateLogin
import com.we.meet.data.repository.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class SpeakerIdentificationContractTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val responseAdapter = moshi.adapter(IdentityResponseDto::class.java)
    private val optionsAdapter = moshi.adapter(IdentityOptionsDto::class.java)
    private class Login(var viewer: String = OWNER, var session: String = "first-login")
    private inner class Fixture(val login: Login = Login(), reply: (Request) -> String = { responseAdapter.toJson(response()) }) {
        val requests = mutableListOf<Pair<Request, String>>()
        var response: (Request) -> String = reply
        val api = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requirePrivateLogin(request, login.session)
            requests += request to request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(response(request).toResponseBody("application/json".toMediaType())).build()
        }.build()).addConverterFactory(MoshiConverterFactory.create(moshi).withNullSerialization()).build().create(SpeakerIdentificationApi::class.java)
        val repository = SpeakerIdentificationRepository(api, { login.viewer }, { login.session })
        val client = repository.open(OWNER, RECORD)
    }
    @Test fun personalSubmissionIncludesExplicitNullAndOnlyBoundIds() = runBlocking {
        val f = Fixture(reply = { responseAdapter.toJson(response(processing = true)) })
        assertTrue(f.client.submit(submission()).isSuccess)
        val (request, body) = f.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/v1.0/meeting-records/$RECORD/speaker-identification/", request.url.encodedPath)
        assertEquals(OWNER, request.header("X-Voiceprint-Owner"))
        assertEquals("no-store", request.header("Cache-Control"))
        assertEquals("first-login", request.tag(PrivateLogin::class.java)?.session)
        assertFalse(request.headers.toString().contains("first-login"))
        assertEquals("""{"request_key":"$KEY","expected_revision":1,"organization_id":null,"user_ids":["$OWNER"],"speaker_ids":["$SPEAKER"]}""", body)
    }
    @Test fun personalDirectoryUsesExplicitScopeAndRequiresOnlyTheOwner() = runBlocking {
        val f = Fixture(reply = { """{"record_revision":1,"organization_id":null,"results":[{"id":"$OWNER","name":"Synthetic"}],"next_offset":null}""" })
        assertTrue(f.client.candidates(null, 1, "A B", 25).isSuccess)
        val request = f.requests.single().first
        assertEquals("personal", request.url.queryParameter("organization_id"))
        assertEquals("A B", request.url.queryParameter("q"))
        assertEquals("25", request.url.queryParameter("offset"))
        f.response = { """{"record_revision":1,"organization_id":null,"results":[{"id":"$ORG","name":"Another person"}],"next_offset":null}""" }
        assertTrue(f.client.candidates(null, 1).isFailure)
    }
    @Test fun optionsRequireTheExactRevisionAndConstrainedOrganization() = runBlocking {
        val f = Fixture(reply = { optionsAdapter.toJson(options()) })
        assertTrue(f.client.options(1).isSuccess)
        f.response = { optionsAdapter.toJson(options(2)) }
        assertTrue(f.client.options(1).isFailure)
        f.response = { optionsAdapter.toJson(options().copy(requiredOrganizationId = RECORD, personalAllowed = false)) }
        assertTrue(f.client.options(1).isFailure)
    }
    @Test fun directoryBoundsAndDuplicatePeopleFailClosed() = runBlocking {
        val f = Fixture(reply = { """{"record_revision":1,"organization_id":"$ORG","results":[{"id":"$OWNER","name":"One"},{"id":"$OWNER","name":"Two"}],"next_offset":null}""" })
        assertTrue(f.client.candidates(ORG, 1).isFailure)
        assertTrue(f.client.candidates(ORG, 1, "x".repeat(81)).isFailure)
        assertTrue(f.client.options(1, 10001).isFailure)
        assertEquals(1, f.requests.size)
    }
    @Test fun submissionAcknowledgmentMustMatchKeyScopeVersionAndTargets() = runBlocking {
        for (value in listOf(response(key = RECORD), response().copy(request = response().request!!.copy(organizationId = ORG)),
            response(revision = 2).copy(request = response().request!!.copy(sourceRevision = 2)),
            response().copy(request = response().request!!.copy(jobs = listOf(response().request!!.jobs.single().copy(speakerId = ORG)))))) {
            val f = Fixture(reply = { responseAdapter.toJson(value) })
            assertTrue(f.client.submit(submission()).isFailure)
        }
    }
    @Test fun unsafeSuggestionsAndMalformedClipsNeverReachTheUI() = runBlocking {
        val base = suggestion()
        for (value in listOf(base.copy(candidate = IdentityPersonDto("bad", "Name")), base.copy(result = "magic"),
            base.copy(clipCount = 13), base.copy(speechMs = 30001), base.copy(queryIntervals = listOf(IdentityIntervalDto(0, 11000))),
            base.copy(queryIntervals = listOf(IdentityIntervalDto(10, 4010), IdentityIntervalDto(4000, 8000))),
            base.copy(state = "confirmed"), base.copy(verificationUnavailable = true))) {
            val input = response().let { it.copy(request = it.request!!.copy(jobs = listOf(it.request.jobs.single().copy(suggestion = value)))) }
            val f = Fixture(reply = { responseAdapter.toJson(input) })
            assertTrue(value.toString(), f.client.read().isFailure)
        }
        val partial = response().let { it.copy(request = it.request!!.copy(processing = true)) }
        assertTrue(Fixture(reply = { responseAdapter.toJson(partial) }).client.read().isFailure)
    }
    @Test fun pendingAndHistoricalResponsesRemainReadableWithoutPrivateNames() = runBlocking {
        for (value in listOf(response(processing = true), response().let { it.copy(request = it.request!!.copy(jobs = listOf(it.request.jobs.single().copy(
            suggestion = suggestion().copy(state = "confirmed", canConfirm = false, candidate = null, queryIntervals = emptyList()))))) })) {
            assertTrue(Fixture(reply = { responseAdapter.toJson(value) }).client.read(KEY).isSuccess)
        }
    }
    @Test fun configRequiresBothSwitchesAndMissingFlagsStayDisabled() = runBlocking {
        for ((body, expected) in listOf("{}" to false, """{"speaker_identity":{"enabled":true}}""" to false,
            """{"speaker_identity":{"enabled":false,"matching_enabled":true}}""" to false,
            """{"speaker_identity":{"enabled":true,"matching_enabled":true}}""" to true)) {
            assertEquals(expected, Fixture(reply = { body }).client.enabled().getOrThrow())
        }
    }
    @Test fun cancellationAndDecisionsUseStrictBodiesWithNoClientNames() = runBlocking {
        val f = Fixture()
        assertTrue(f.client.cancel(KEY, 1).isSuccess)
        assertEquals("DELETE", f.requests.single().first.method)
        assertEquals("""{"request_key":"$KEY","expected_revision":1}""", f.requests.single().second)
        f.response = { """{"id":"$SPEAKER","label":"Speaker 0","identity_type":"diarized","display_name":"Synthetic Reviewer","record_revision":2,"attribution_kind":"member","attributed_user_id":"$OWNER"}""" }
        assertTrue(f.client.decide(SPEAKER, suggestion(), true, 1).isSuccess)
        assertEquals("""{"action":"confirm_suggestion","suggestion_id":"$SUGGESTION","expected_revision":1}""", f.requests.last().second)
        f.response = { """{"id":"$SPEAKER","label":"Speaker 0","identity_type":"diarized","display_name":"Wrong person","record_revision":2,"attribution_kind":"member","attributed_user_id":"$ORG"}""" }
        assertTrue(f.client.decide(SPEAKER, suggestion(), true, 1).isFailure)
    }
    @Test fun sameAccountReloginCannotSendAnOldOperation() = runBlocking {
        val f = Fixture(); f.login.session = "second-login"
        assertTrue(f.client.read().exceptionOrNull() is IdentityLoginChangedException)
        assertTrue(f.requests.isEmpty())
        assertThrows(IOException::class.java) { requirePrivateLogin(Request.Builder().url("https://fixture.invalid/").tag(PrivateLogin::class.java, PrivateLogin("first-login")).build(), "second-login") }
        Unit
    }
    @Test fun anAccountSwitchDuringTheRequestDropsTheLateResponse() = runBlocking {
        lateinit var f: Fixture
        f = Fixture(reply = { f.login.viewer = ORG; responseAdapter.toJson(response()) })
        assertTrue(f.client.read().exceptionOrNull() is IdentityLoginChangedException)
    }
    @Test fun pendingCommandIsCopiedSurvivesReopeningAndClearsOnRelogin() {
        val f = Fixture(); val ids = mutableListOf(OWNER)
        f.client.remember(submission().copy(userIds = ids)); ids.clear()
        assertEquals(listOf(OWNER), f.repository.open(OWNER, RECORD).pending()!!.userIds)
        f.client.acknowledge(ORG); assertNotNull(f.client.pending())
        f.client.acknowledge(KEY); assertNull(f.client.pending())
        f.client.remember(submission()); f.login.session = "second-login"
        assertNull(f.repository.open(OWNER, RECORD).pending())
    }
    @Test fun pendingCommandCapacityNeverEvictsAnUncertainSubmission() {
        val f = Fixture()
        repeat(20) { f.repository.open(OWNER, UUID(0, it.toLong()).toString()).remember(submission()) }
        assertThrows(IllegalArgumentException::class.java) { f.client.remember(submission()) }
        assertNotNull(f.repository.open(OWNER, UUID(0, 0).toString()).pending())
    }
    @Test fun anotherPanelCannotReplaceAnUnacknowledgedCommandForTheSameRecord() {
        val f = Fixture()
        f.client.remember(submission())
        val second = f.repository.open(OWNER, RECORD)
        assertThrows(IllegalArgumentException::class.java) { second.remember(submission().copy(requestKey = ORG)) }
        assertThrows(IllegalArgumentException::class.java) { second.remember(submission().copy(userIds = listOf(ORG))) }
        assertEquals(submission(), second.pending())
        second.remember(submission())
        f.login.session = "second-login"
        assertFalse(f.client.allowed())
        assertNull(f.repository.open(OWNER, RECORD).pending())
    }
    @Test fun privateTimeoutIsBoundedAndExternalCancellationIsNotSwallowed() = runTest {
        val f = Fixture()
        val slow = object : SpeakerIdentificationApi by f.api {
            override suspend fun read(record: String, owner: String, login: PrivateLogin, key: String?): IdentityResponseDto { delay(20000); return response() }
        }
        val client = SpeakerIdentificationRepository(slow, { OWNER }, { "first-login" }).open(OWNER, RECORD)
        assertTrue(client.read().exceptionOrNull() is IdentityRequestTimeoutException)
        val job = async(start = CoroutineStart.UNDISPATCHED) { client.read() }
        job.cancel(); assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertTrue(f.requests.isEmpty())
    }
}
