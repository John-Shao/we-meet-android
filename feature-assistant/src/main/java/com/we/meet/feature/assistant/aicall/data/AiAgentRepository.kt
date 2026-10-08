package com.we.meet.feature.assistant.aicall.data

import com.we.meet.feature.assistant.aicall.model.AiAgentConfigResponse
import com.we.meet.feature.assistant.aicall.model.AiCallOffer
import com.we.meet.feature.assistant.aicall.model.AiCallAnswer
import com.we.meet.feature.assistant.aicall.model.AiCallSetupException
import com.we.meet.feature.assistant.R
import org.json.JSONObject
import retrofit2.HttpException

class AiAgentRepository(private val api: AiAgentApi) {
    fun track(answer: AiCallAnswer, lost: () -> Unit): DirectAILease? = answer.session_lease?.let {
        DirectAILease(it, api::sessionLease, lost).also(DirectAILease::start)
    }
    suspend fun fetchConfig(): AiAgentConfigResponse = api.fetchConfig()
    suspend fun exchangeOffer(offer: AiCallOffer): AiCallAnswer {
        if (offer.transport != "aoq") return api.exchangeOffer(offer)
        val answer = try {
            api.exchangeOffer(offer)
        } catch (error: HttpException) {
            // Older backends reject the empty SDP without understanding AOQ.
            // Peek preserves the body for the ordinary error translator.
            val body = error.response()?.errorBody()?.source()?.let { source ->
                source.request(4096)
                source.buffer.clone().readUtf8()
            }
            val requiresSdp = runCatching { JSONObject(body ?: "{}").has("sdp") }.getOrDefault(false)
            if ((error.code() == 400 && requiresSdp) || error.code() == 404 || error.code() == 405) {
                throw AiCallSetupException(R.string.assistant_error_aoq_backend, "allocation_http_${error.code()}", error)
            }
            throw error
        }
        if (answer.aoq == null) {
            throw AiCallSetupException(R.string.assistant_error_aoq_backend, "allocation_missing_credentials")
        }
        return answer
    }
}
