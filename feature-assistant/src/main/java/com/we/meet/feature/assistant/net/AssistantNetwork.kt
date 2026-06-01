package com.we.meet.feature.assistant.net

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.feature.assistant.AssistantDeps
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Builds the feature's Retrofit on top of the host-provided authenticated
 * OkHttp ([AssistantDeps.authedOkHttp]) so the assistant reuses the host's
 * Bearer token + 401 refresh instead of owning auth.
 */
internal object AssistantNetwork {
    fun retrofit(deps: AssistantDeps): Retrofit {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val base = if (deps.baseUrl.endsWith("/")) deps.baseUrl else deps.baseUrl + "/"
        return Retrofit.Builder()
            .baseUrl(base)
            .client(deps.authedOkHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }
}
