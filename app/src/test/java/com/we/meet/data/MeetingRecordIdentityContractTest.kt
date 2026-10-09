package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.RecordIdentityDecisionRequest
import com.we.meet.data.repository.MeetingRecordRepository
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Exercise the annotated Retrofit methods, JSON omission and repository guards together. */
class MeetingRecordIdentityContractTest {
    private val recordId = "11111111-1111-4111-8111-111111111111"
    private val speakerId = "22222222-2222-4222-8222-222222222222"
    private val memberId = "33333333-3333-4333-8333-333333333333"
    private val otherId = "44444444-4444-4444-8444-444444444444"
    private val departmentId = "55555555-5555-4555-8555-555555555555"
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private inner class Fixture(
        val respond: (Request, String, Int) -> String,
    ) {
        val viewer = AtomicReference("editor")
        val requests = mutableListOf<Pair<Request, String>>()
        private var recordReads = 0
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() } ?: ""
            requests += request to body
            if (request.url.encodedPath == "/api/v1.0/meeting-records/$recordId/") recordReads++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("Fixture").body(respond(request, body, recordReads).toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build()
            .create(MeetingRecordApi::class.java)
        val repository = MeetingRecordRepository(api) { viewer.get() }
    }

    private fun record(revision: Int) = """{
        "id":"$recordId","source_type":"upload","title":"Record",
        "origin_at":"2026-10-10T00:00:00Z","revision":$revision,
        "capabilities":{"read_transcript":true}
    }"""

    private fun speaker(kind: String = "custom", label: String = "Guest", user: String? = null) = """{
        "id":"$speakerId","label":"Speaker 1","identity_type":"diarized",
        "manual_label":"$label","attribution_kind":"$kind","record_revision":4,
        "attributed_user_id":${user?.let { "\"$it\"" } ?: "null"}
    }"""

    private fun fixture(updated: String = speaker(), concurrent: Boolean = false) = Fixture { request, _, reads ->
        if (request.method != "GET") updated else record(if (reads == 1) 3 else if (concurrent) 5 else 4)
    }

    @Test fun customLabelUsesTheStrictWirePayloadAndAcceptsALaterRevision() = runBlocking {
        val fixture = fixture(concurrent = true)
        val result = fixture.repository.speakerIdentityDecision(
            "editor", recordId, speakerId, RecordIdentityDecisionRequest("set_label", 3, label = "Guest"),
        )
        assertTrue(result.isSuccess)
        val (request, body) = fixture.requests.single { it.first.method == "POST" }
        assertEquals("/api/v1.0/meeting-records/$recordId/speakers/$speakerId/identity-decision/", request.url.encodedPath)
        assertEquals("no-store", request.header("Cache-Control"))
        assertEquals("""{"action":"set_label","expected_revision":3,"label":"Guest"}""", body)
    }

    @Test fun clearOmitsBothOptionalFields() = runBlocking {
        val fixture = fixture(speaker(kind = "none", label = ""))
        val result = fixture.repository.speakerIdentityDecision(
            "editor", recordId, speakerId, RecordIdentityDecisionRequest("clear", 3),
        )
        assertTrue(result.isSuccess)
        assertEquals("""{"action":"clear","expected_revision":3}""", fixture.requests.single { it.first.method == "POST" }.second)
    }

    @Test fun doesNotWriteAfterAnAccountSwitchDuringThePrerequisiteRead() = runBlocking {
        lateinit var fixture: Fixture
        fixture = Fixture { _, _, _ -> fixture.viewer.set("other-editor"); record(3) }
        assertTrue(fixture.repository.speakerIdentityDecision(
            "editor", recordId, speakerId, RecordIdentityDecisionRequest("set_label", 3, label = "Guest"),
        ).isFailure)
        assertTrue(fixture.requests.none { it.first.method == "POST" })
    }

    @Test fun legacyAttributionAlsoStopsAfterAnAccountSwitch() = runBlocking {
        lateinit var fixture: Fixture
        fixture = Fixture { _, _, _ -> fixture.viewer.set("other-editor"); record(3) }
        assertTrue(fixture.repository.attributeSpeaker("editor", recordId, 3, speakerId, memberId).isFailure)
        assertTrue(fixture.requests.none { it.first.method == "PATCH" })
    }

    @Test fun legacyAttributionDoesNotMisreportAnAcceptedConcurrentWrite() = runBlocking {
        val fixture = fixture(speaker(kind = "member", label = "", user = memberId), concurrent = true)
        assertTrue(fixture.repository.attributeSpeaker("editor", recordId, 3, speakerId, memberId).isSuccess)
    }

    @Test fun doesNotReportTheWrongMemberAsSuccessfullySelected() = runBlocking {
        val fixture = fixture(speaker(kind = "member", label = "", user = otherId))
        assertTrue(fixture.repository.speakerIdentityDecision(
            "editor", recordId, speakerId, RecordIdentityDecisionRequest("select_contact", 3, contactRef = "member:$memberId"),
        ).isFailure)
    }

    @Test fun clearMustActuallyClearTheIdentity() = runBlocking {
        val fixture = fixture(speaker(kind = "member", label = "", user = memberId))
        assertTrue(fixture.repository.speakerIdentityDecision(
            "editor", recordId, speakerId, RecordIdentityDecisionRequest("clear", 3),
        ).isFailure)
    }

    @Test fun contactsAcceptTheServersFullDepartmentAndOrganizationNameBounds() = runBlocking {
        val longName = "x".repeat(255)
        val fixture = Fixture { _, _, _ -> """{"results":[{
            "ref":"$departmentId","kind":"department","name":"$longName",
            "organization_name":"$longName","department_id":"$departmentId"
        }],"next_offset":null}""" }
        assertTrue(fixture.repository.speakerContacts("editor", recordId, kind = "departments").isSuccess)
        val request = fixture.requests.single().first
        assertEquals("/api/v1.0/meeting-records/$recordId/speaker-contacts/", request.url.encodedPath)
        assertEquals("departments", request.url.queryParameter("kind"))
        assertEquals("0", request.url.queryParameter("offset"))
        assertEquals("no-store", request.header("Cache-Control"))
    }

    @Test fun contactsRejectAReferenceWithTheWrongKind() = runBlocking {
        val fixture = Fixture { _, _, _ -> """{"results":[{
            "ref":"external:$memberId","kind":"member","name":"Ada"
        }],"next_offset":null}""" }
        assertTrue(fixture.repository.speakerContacts("editor", recordId).isFailure)
    }

    @Test fun rejectsUnicodeFormattingCharactersAndUnpairedSurrogatesBeforeSending() = runBlocking {
        val fixture = fixture()
        for (label in listOf("Guest" + String(Character.toChars(0xE0001)), "Guest\uD800")) {
            assertTrue(fixture.repository.speakerIdentityDecision(
                "editor", recordId, speakerId, RecordIdentityDecisionRequest("set_label", 3, label = label),
            ).isFailure)
        }
        assertTrue(fixture.requests.isEmpty())
    }
}
