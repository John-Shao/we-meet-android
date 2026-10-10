package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.VoiceprintCallFixtures as F
import com.we.meet.data.api.VoiceprintApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.auth.PrivateLogin
import com.we.meet.data.auth.requirePrivateLogin
import com.we.meet.data.repository.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class VoiceprintCallContractTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private inner class Fixture {
        var viewer = VoiceprintFixtures.OWNER
        var login = "synthetic-login"
        var reply: (Request) -> Pair<Int, Any> = { 200 to F.connection() }
        val requests = CopyOnWriteArrayList<Request>()
        val bodies = CopyOnWriteArrayList<String>()
        val repository = VoiceprintRepository(Retrofit.Builder().baseUrl("https://fixture.invalid/")
            .client(OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                val request = chain.request(); requirePrivateLogin(request, login)
                requests += request; bodies += request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
                val (code, value) = reply(request)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Synthetic").code(code)
                    .body(moshi.adapter(Any::class.java).toJson(value).toResponseBody("application/json".toMediaType())).build()
            }.build()).addConverterFactory(MoshiConverterFactory.create(moshi).withNullSerialization())
            .build().create(VoiceprintApi::class.java), { viewer }, { login })
        val client = repository.openCall(viewer, F.ROOM, F.PARTICIPANT)
    }
    @Test fun connectionIsOwnerBoundNoStoreAndContainsOnlyExactRtcSids() = runBlocking {
        val f = Fixture(); assertEquals(F.connection(), f.client.read().getOrThrow())
        val request = f.requests.single(); assertEquals("GET", request.method)
        assertEquals("/api/v1.0/voiceprint/sampling-connection/", request.url.encodedPath)
        assertEquals(setOf("room_sid", "participant_sid"), request.url.queryParameterNames)
        assertEquals(F.ROOM, request.url.queryParameter("room_sid")); assertEquals(F.PARTICIPANT, request.url.queryParameter("participant_sid"))
        assertEquals(VoiceprintFixtures.OWNER, request.header("X-Voiceprint-Owner")); assertEquals("no-store", request.header("Cache-Control"))
        assertEquals("synthetic-login", request.tag(PrivateLogin::class.java)?.session); assertNull(request.header("X-Private-Login"))
        assertEquals("", f.bodies.single()); assertFalse(F.connection().toString().contains(VoiceprintFixtures.ENROLLMENT))
    }
    @Test fun featureNeedsBothExplicitFlagsAndLegacyConfigDoesNotEnableIt() = runBlocking {
        val f = Fixture()
        for (flags in listOf(null, emptyMap(), mapOf("enabled" to true), mapOf("enabled" to false, "sampling_enabled" to true), mapOf("enabled" to true, "sampling_enabled" to false))) {
            f.reply = { 200 to mapOf("speaker_identity" to flags) }; assertFalse(f.client.capability().getOrThrow())
        }
        f.reply = { 200 to mapOf("speaker_identity" to mapOf("enabled" to true, "sampling_enabled" to true)) }
        assertTrue(f.client.capability().getOrThrow()); assertTrue(f.requests.all { it.method == "GET" && it.url.encodedPath == "/api/v1.0/config/" })
    }
    @Test fun invalidIdentifiersCannotOpenATransport() {
        val f = Fixture()
        for ((room, participant) in listOf("" to F.PARTICIPANT, "RM/path" to F.PARTICIPANT, F.ROOM to "PA secret", "a".repeat(65) to F.PARTICIPANT)) {
            assertTrue(runCatching { f.repository.openCall(VoiceprintFixtures.OWNER, room, participant) }.isFailure)
        }
        assertTrue(f.requests.isEmpty())
    }
    @Test fun declarationRequiresAnObservedBindingAndUsesExactRevisionWithoutMedia() = runBlocking {
        val f = Fixture(); assertTrue(f.client.declare(F.connection(), false, false, "headset").isFailure); assertTrue(f.requests.isEmpty())
        val snapshot = f.client.read().getOrThrow(); f.reply = { 200 to F.ready().control }
        assertTrue(f.client.declare(snapshot, false, false, "headset").isSuccess)
        assertEquals("PATCH", f.requests.last().method)
        assertEquals("""{"session_id":"${VoiceprintFixtures.ENROLLMENT}","participant_sid":"${F.PARTICIPANT}","expected_revision":3,"paused":false,"shared_microphone":false,"device_group":"headset"}""", f.bodies.last())
        val before = f.requests.size
        assertTrue(f.client.declare(snapshot.copy(organizationId = VoiceprintFixtures.ORGANIZATION), false, false, "headset").isFailure)
        assertTrue(f.client.declare(snapshot, false, false, "raw-private-device-id").isFailure); assertEquals(before, f.requests.size)
    }
    @Test fun positiveRuntimeRequiresFreshProofPermissionAndReadyDeclaration() = runBlocking {
        val f = Fixture(); val value = F.ready()
        f.reply = { 200 to value }; assertTrue(f.client.read().isSuccess)
        for (bad in listOf(value.copy(permission = value.permission.copy(allowAccumulation = false)), value.copy(permission = value.permission.copy(available = false)),
            value.copy(control = value.control.copy(paused = true)), value.copy(control = value.control.copy(sharedMicrophone = true)),
            value.copy(control = value.control.copy(deviceGroup = "")), value.copy(control = value.control.copy(state = "paused")),
            value.copy(control = value.control.copy(runtime = value.control.runtime.copy(updatedAt = null))),
            value.copy(control = value.control.copy(runtime = value.control.runtime.copy(updatedAt = "2099-10-10T00:00:04Z"))),
            value.copy(control = value.control.copy(runtime = value.control.runtime.copy(updatedAt = "2099-10-09T23:59:58Z"))))) {
            f.reply = { 200 to bad }; assertTrue(f.client.read().isFailure)
        }
    }
    @Test fun responseRejectsInvalidLimitsDatesEnumsCountersAndPrivateFields() = runBlocking {
        val f = Fixture(); val value = F.connection()
        for (bad in listOf(value.copy(roomSid = "RM_other"), value.copy(observedAt = "invalid"), value.copy(organizationName = "Personal leak"),
            value.copy(limits = value.limits.copy(clipMs = 2000)), value.copy(limits = value.limits.copy(sessionMs = 60001)),
            value.copy(limits = value.limits.copy(dailyMs = 120001)), value.copy(limits = value.limits.copy(candidateRetentionSeconds = 86401)),
            value.copy(permission = value.permission.copy(version = -1)), value.copy(control = value.control.copy(participantSid = "PA_other")),
            value.copy(control = value.control.copy(sessionId = "private")), value.copy(control = value.control.copy(revision = -1)),
            value.copy(control = value.control.copy(deviceGroup = "private microphone")), value.copy(control = value.control.copy(state = "unknown")),
            value.copy(control = value.control.copy(stopReason = "private detail")), value.copy(control = value.control.copy(runtime = value.control.runtime.copy(state = "unknown"))),
            value.copy(control = value.control.copy(runtime = value.control.runtime.copy(reason = "secret"))),
            value.copy(control = value.control.copy(runtime = value.control.runtime.copy(remainingMs = VoiceprintCallRemainingDto(-1, 120000)))))) {
            f.reply = { 200 to bad }; assertTrue(f.client.read().isFailure)
        }
        f.reply = { 200 to value }; assertTrue(f.client.read().isSuccess)
    }
    @Test fun firstSuccessfulConnectionPinsOrganizationAndSessionUntilClientReplacement() = runBlocking {
        val f = Fixture(); assertTrue(f.client.read().isSuccess)
        f.reply = { 200 to F.connection(VoiceprintFixtures.ORGANIZATION) }; assertTrue(f.client.read().isFailure)
        f.reply = { 200 to F.connection().let { it.copy(control = it.control.copy(sessionId = VoiceprintFixtures.DELETION)) } }; assertTrue(f.client.read().isFailure)
        f.reply = { 200 to F.connection() }; assertTrue(f.client.read().isSuccess)
    }
    @Test fun wrongDeclarationAcknowledgementIsRejectedAndNeverReplayed() = runBlocking {
        val f = Fixture(); val snapshot = f.client.read().getOrThrow()
        f.reply = { 200 to F.connection().control.copy(participantSid = "PA_other") }; assertTrue(f.client.declare(snapshot, true, true, "").isFailure)
        f.reply = { 200 to F.connection().control.copy(sessionId = VoiceprintFixtures.DELETION) }; assertTrue(f.client.declare(snapshot, true, true, "").isFailure)
        f.reply = { throw IOException("Synthetic lost ack") }; assertTrue(f.client.declare(snapshot, false, false, "headset").isFailure)
        assertEquals(4, f.requests.size)
    }
    @Test fun accumulationOffUsesTrustedScopeAndVersionWithNoOtherPermissions() = runBlocking {
        for (organization in listOf(null, VoiceprintFixtures.ORGANIZATION)) {
            val f = Fixture(); f.reply = { 200 to F.connection(organization) }; val snapshot = f.client.read().getOrThrow()
            f.reply = { 200 to VoiceprintFixtures.settings(organization).copy(version = 8) }
            assertTrue(f.client.disableAccumulation(snapshot).isSuccess)
            val scope = organization?.let { "\"$it\"" } ?: "null"
            assertEquals("""{"organization_id":$scope,"expected_version":7,"allow_accumulation":false}""", f.bodies.last())
        }
    }
    @Test fun changedLoginBeforeAndAfterResponseRejectsPrivateResults() = runBlocking {
        val before = Fixture(); before.login = "new-login"; assertTrue(before.client.read().exceptionOrNull() is IdentityLoginChangedException); assertTrue(before.requests.isEmpty())
        val during = Fixture(); during.reply = { during.login = "new-login"; 200 to F.connection() }
        assertTrue(during.client.read().exceptionOrNull() is IdentityLoginChangedException)
        assertTrue(during.client.capability().exceptionOrNull() is IdentityLoginChangedException); assertEquals(1, during.requests.size)
    }
}
