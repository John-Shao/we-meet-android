package com.we.meet.core.directory.net

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.core.directory.DirectoryDeps
import com.we.meet.core.directory.data.DirectoryApi
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Builds this module's Retrofit on top of the host's authenticated OkHttp —
 * same pattern as feature-im's ImNetwork.
 */
object DirectoryNetwork {

    fun directoryApi(deps: DirectoryDeps): DirectoryApi {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val base = if (deps.baseUrl.endsWith("/")) deps.baseUrl else deps.baseUrl + "/"
        return Retrofit.Builder()
            .baseUrl(base)
            .client(deps.authedOkHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DirectoryApi::class.java)
    }
}
