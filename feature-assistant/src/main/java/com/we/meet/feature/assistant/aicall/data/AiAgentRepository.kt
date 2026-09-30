package com.we.meet.feature.assistant.aicall.data

import com.we.meet.feature.assistant.aicall.model.AiAgentConfigResponse
import com.we.meet.feature.assistant.aicall.model.AiCallOffer
import com.we.meet.feature.assistant.aicall.model.AiCallAnswer

class AiAgentRepository(private val api: AiAgentApi) {
    suspend fun fetchConfig(): AiAgentConfigResponse = api.fetchConfig()
    suspend fun exchangeOffer(offer: AiCallOffer): AiCallAnswer = api.exchangeOffer(offer)
}
