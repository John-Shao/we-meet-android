package com.we.meet.feature.assistant.aicall.model

import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.aicall.data.AiAgentApi
import com.we.meet.feature.assistant.aicall.data.AiAgentRepository
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class AoqAllocationTest {
    private fun repository(exchange: (AiCallOffer) -> AiCallAnswer) = AiAgentRepository(object : AiAgentApi {
        override suspend fun fetchConfig() = AiAgentConfigResponse()
        override suspend fun exchangeOffer(offer: AiCallOffer) = exchange(offer)
    })
    private val offer = AiCallOffer("", "test-profile", transport = "aoq")

    @Test fun oldBackendSdpRejectionHasAnActionableMessage() = runTest {
        val http = HttpException(Response.error<AiCallAnswer>(400, "{\"sdp\":[\"This field may not be blank.\"]}".toResponseBody()))
        val result = runCatching { repository { throw http }.exchangeOffer(offer) }.exceptionOrNull()
        assertTrue(result is AiCallSetupException)
        assertEquals(R.string.assistant_error_aoq_backend, (result as AiCallSetupException).messageRes)
    }

    @Test fun missingCredentialsCannotStartAnAoqEngine() = runTest {
        val result = runCatching { repository { AiCallAnswer("sdp", "Tina", "prompt") }.exchangeOffer(offer) }.exceptionOrNull()
        assertEquals("allocation_missing_credentials", (result as AiCallSetupException).stage)
    }

    @Test fun unrelatedHttpErrorsPreserveTheirBody() = runTest {
        val body = "{\"detail\":\"Profile unavailable\"}"
        val http = HttpException(Response.error<AiCallAnswer>(400, body.toResponseBody()))
        val result = runCatching { repository { throw http }.exchangeOffer(offer) }.exceptionOrNull()
        assertSame(http, result)
        assertEquals(body, http.response()!!.errorBody()!!.string())
    }

    @Test fun webRtcStillAcceptsAnSdpAnswerWithoutAoqCredentials() = runTest {
        val answer = AiCallAnswer("sdp", "Tina", "prompt")
        assertSame(answer, repository { answer }.exchangeOffer(offer.copy(transport = "webrtc", sdp = "sdp")))
    }
}
