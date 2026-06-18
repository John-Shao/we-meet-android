package com.we.meet.feature.im.net

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.feature.im.ImDeps
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Builds the feature module's Retrofit on top of the host's authenticated OkHttp.
 *
 * The host's interceptor chain already handles OIDC Bearer auth + 401 silent
 * refresh — the feature just attaches its own typed API surface on top.
 */
internal object ImNetwork {

    fun retrofit(deps: ImDeps): Retrofit {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val base = if (deps.baseUrl.endsWith("/")) deps.baseUrl else deps.baseUrl + "/"
        return Retrofit.Builder()
            .baseUrl(base)
            .client(deps.authedOkHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }
}
