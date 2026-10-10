package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.VoiceprintFixtures as F
import com.we.meet.data.api.VoiceprintApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.auth.PrivateLogin
import com.we.meet.data.auth.requirePrivateLogin
import com.we.meet.data.repository.*
import com.we.meet.data.voiceprint.VoiceprintWave
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

class VoiceprintContractTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private class Login(var viewer: String = F.OWNER, var session: String = "synthetic-login")
    private inner class Fixture(val organization: String? = null, val login: Login = Login()) {
        val requests = CopyOnWriteArrayList<Request>()
        val bodies = CopyOnWriteArrayList<ByteArray>()
        var reply: (Request) -> Pair<Int, Any> = { 200 to F.settings(organization) }
        val client = VoiceprintRepository(Retrofit.Builder().baseUrl("https://fixture.invalid/")
            .client(OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                val request = chain.request(); requirePrivateLogin(request, login.session)
                requests += request
                bodies += request.body?.let { Buffer().also(it::writeTo).readByteArray() } ?: byteArrayOf()
                val (status, value) = reply(request)
                val jsonValue = if (value is VoiceprintPageDto<*>) mapOf("results" to value.results, "next_offset" to value.nextOffset) else value
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Synthetic").code(status)
                    .body(if (value is ByteArray) value.toResponseBody("audio/wav".toMediaType()) else moshi.adapter(Any::class.java).toJson(jsonValue).toResponseBody("application/json".toMediaType())).build()
            }.build())
            .addConverterFactory(MoshiConverterFactory.create(moshi).withNullSerialization()).build().create(VoiceprintApi::class.java), { login.viewer }, { login.session }).open(F.OWNER, organization)
    }
    @Test fun consentChangeIncludesExplicitPersonalNullAndOnlyOnePermission() = runBlocking {
        val f = Fixture(); assertTrue(f.client.change(VoiceprintPermission.ENROLLMENT, true, 1).isSuccess)
        assertEquals("""{"organization_id":null,"expected_version":1,"allow_enrollment":true}""", f.bodies.single().toString(Charsets.UTF_8))
        val request = f.requests.single()
        assertEquals("PATCH", request.method); assertEquals(F.OWNER, request.header("X-Voiceprint-Owner")); assertEquals("no-store", request.header("Cache-Control"))
        assertEquals("synthetic-login", request.tag(PrivateLogin::class.java)?.session)
        assertNull(request.header("X-Private-Login"))
    }
    @Test fun organizationPolicyRequiresAnExplicitOrganizationAndVersion() = runBlocking {
        val personal = Fixture(); assertTrue(personal.client.policy(true, 0).isFailure); assertTrue(personal.requests.isEmpty())
        val f = Fixture(F.ORGANIZATION); f.reply = { 200 to VoiceprintPolicyDto(true, 2) }
        assertTrue(f.client.policy(true, 1).isSuccess)
        assertTrue(f.requests.single().url.encodedPath.contains(F.ORGANIZATION))
        assertEquals("""{"enabled":true,"expected_version":1}""", f.bodies.single().toString(Charsets.UTF_8))
    }
    @Test fun settingsRejectTheWrongScopeInvalidDateDuplicateProfilesAndMissingFlags() = runBlocking {
        val f = Fixture()
        for (value in listOf(F.settings(F.ORGANIZATION), F.settings().copy(profiles = F.settings().profiles + F.settings().profiles),
            F.settings().copy(profiles = listOf(F.settings().profiles.single().copy(confirmedAt = "invalid"))), mapOf("organization_id" to null, "available" to true))) {
            f.reply = { 200 to value }; assertTrue(f.client.settings().isFailure)
        }
    }
    @Test fun pagesRejectDuplicatesBackwardsOffsetsOversizedPagesAndNegativePolicyVersions() = runBlocking {
        val f = Fixture(); val scope = VoiceprintScopeDto(F.ORGANIZATION, "Synthetic", true, VoiceprintPolicyDto(false, 0))
        for (page in listOf(VoiceprintPageDto(listOf(scope, scope), null), VoiceprintPageDto(listOf(scope), 0),
            VoiceprintPageDto(List(26) { scope }, null), VoiceprintPageDto(listOf(scope.copy(policy = VoiceprintPolicyDto(false, -1))), null))) {
            f.reply = { 200 to page }; assertTrue(f.client.scopes().isFailure)
        }
        val before = f.requests.size; assertTrue(f.client.scopes(10001).isFailure); assertEquals(before, f.requests.size)
    }
    @Test fun validPagesDecodeTheirGenericTypesAndUseTheExplicitScopeAndOffset() = runBlocking {
        val f = Fixture(F.ORGANIZATION)
        val scope = VoiceprintScopeDto(F.ORGANIZATION, "Synthetic", true, VoiceprintPolicyDto(false, 0))
        f.reply = { 200 to VoiceprintPageDto(listOf(scope), 25) }
        assertEquals(scope, f.client.scopes().getOrThrow().results.single())
        f.reply = { 200 to VoiceprintPageDto(listOf(F.sample()), null) }
        assertEquals(F.sample(), f.client.samples(25).getOrThrow().results.single())
        assertEquals("25", f.requests.last().url.queryParameter("offset")); assertEquals(F.ORGANIZATION, f.requests.last().url.queryParameter("organization_id"))
        f.reply = { 200 to VoiceprintPageDto(listOf(F.deletion()), null) }
        assertEquals(F.deletion(), f.client.deletions(25).getOrThrow().results.single())
    }
    @Test fun beginBindsScopeVersionKeyAndRandomPromptFormat() = runBlocking {
        val f = Fixture(); f.reply = { 201 to F.enrollment() }
        assertTrue(f.client.begin(1, F.KEY, "zh-CN").isSuccess)
        assertEquals("""{"organization_id":null,"expected_version":1,"request_key":"${F.KEY}","locale":"zh-CN"}""", f.bodies.single().toString(Charsets.UTF_8))
        for (value in listOf(F.enrollment().copy(organizationId = F.ORGANIZATION), F.enrollment().copy(consentVersion = 2),
            F.enrollment().copy(challenges = listOf("Synthetic")), F.enrollment().copy(sampleRate = 16000),
            F.enrollment().copy(uploadedSlots = listOf(0, 0)), F.enrollment().copy(uploadToken = "invalid"))) {
            f.reply = { 201 to value }; assertTrue(f.client.begin(1, F.KEY, "en").isFailure)
        }
        assertFalse(F.enrollment().toString().contains(F.token))
    }
    @Test fun uploadUsesExactWavLengthTokenOriginalSlotAndNoJsonEnvelope() = runBlocking {
        val f = Fixture(); f.reply = { 202 to F.sample() }; val bytes = F.wav()
        assertTrue(f.client.upload(F.enrollment(), 4, bytes).isSuccess)
        val request = f.requests.single()
        assertEquals("PUT", request.method); assertTrue(request.url.encodedPath.endsWith("/clips/4/"))
        assertEquals("audio/wav", request.body!!.contentType().toString()); assertEquals(bytes.size.toLong(), request.body!!.contentLength())
        assertEquals(F.token, request.header("X-Voiceprint-Upload-Token")); assertArrayEquals(bytes, f.bodies.single())
        assertEquals(3000L, VoiceprintWave.duration(bytes)); assertTrue(bytes.any { it != 0.toByte() })
        f.reply = { 202 to F.sample().copy(profileId = F.ORGANIZATION) }
        assertTrue(f.client.upload(F.enrollment(), 4, bytes).isFailure)
    }
    @Test fun invalidUploadsDoNotOpenTransport() = runBlocking {
        val f = Fixture()
        assertTrue(f.client.upload(F.enrollment(), 6, F.wav()).isFailure)
        assertTrue(f.client.upload(F.enrollment().copy(uploadToken = null), 0, F.wav()).isFailure)
        assertTrue(f.client.upload(F.enrollment(), 0, byteArrayOf(1)).isFailure)
        assertTrue(f.requests.isEmpty())
    }
    @Test fun sampleMetadataAndAudioRemainScopeBoundAndBounded() = runBlocking {
        val f = Fixture(F.ORGANIZATION); val bytes = F.wav()
        f.reply = { if (it.url.encodedPath.endsWith("/audio/")) 200 to bytes else 200 to F.sample() }
        assertArrayEquals(bytes, f.client.audio(F.sample()).getOrThrow())
        assertEquals(F.ORGANIZATION, f.requests.first().url.queryParameter("organization_id"))
        f.reply = { 200 to F.sample().copy(audioAvailable = false) }
        assertTrue(f.client.audio(F.sample()).isFailure)
        f.reply = { if (it.url.encodedPath.endsWith("/audio/")) 200 to ByteArray(VoiceprintWave.MAX_BYTES + 1) else 200 to F.sample() }
        assertTrue(f.client.audio(F.sample()).isFailure)
    }
    @Test fun confirmationCannotUsePendingQualityAndReceiptsMustMatchTheSample() = runBlocking {
        val f = Fixture(); assertTrue(f.client.decide(F.sample(), true, 1).isFailure); assertTrue(f.requests.isEmpty())
        val ready = F.sample().copy(status = "ready", confirmable = true)
        f.reply = { 200 to ready.copy(status = "confirmed", confirmable = false) }
        assertTrue(f.client.decide(ready, true, 1).isSuccess)
        assertEquals("""{"accepted":true,"expected_version":1}""", f.bodies.single().toString(Charsets.UTF_8))
        f.reply = { 200 to ready.copy(id = F.ORGANIZATION, status = "confirmed", confirmable = false) }
        assertTrue(f.client.decide(ready, true, 1).isFailure)
    }
    @Test fun deleteOnlySendsTheVersionAndStableKeyAndChecksTheReceipt() = runBlocking {
        val f = Fixture(); f.reply = { 202 to F.deletion() }
        assertTrue(f.client.remove(F.PROFILE, 1, F.KEY).isSuccess)
        assertEquals("DELETE", f.requests.single().method)
        assertEquals("""{"expected_version":1,"request_key":"${F.KEY}"}""", f.bodies.single().toString(Charsets.UTF_8))
        f.reply = { 202 to F.deletion().copy(revokedGeneration = 0) }
        assertTrue(f.client.remove(F.PROFILE, 1, F.KEY).isFailure)
    }
    @Test fun sameOwnerReloginFencesBeforeTransportAndLateResults() = runBlocking {
        val before = Fixture(); before.login.session = "next-login"
        assertTrue(before.client.settings().isFailure); assertTrue(before.requests.isEmpty())
        val during = Fixture(); during.reply = { during.login.session = "next-login"; 200 to F.settings() }
        assertTrue(during.client.settings().isFailure)
        assertThrows(IOException::class.java) { requirePrivateLogin(Request.Builder().url("https://fixture.invalid/").tag(PrivateLogin::class.java, PrivateLogin("old")).build(), "new") }
        Unit
    }
}
