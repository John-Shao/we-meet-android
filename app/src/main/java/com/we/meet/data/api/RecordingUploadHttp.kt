package com.we.meet.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/** Media uploads also wait for server-side validation and object storage before receiving 202. */
internal fun recordingUploadHttp(base: OkHttpClient): OkHttpClient = base.newBuilder()
    .readTimeout(2, TimeUnit.MINUTES)
    .writeTimeout(2, TimeUnit.MINUTES)
    .callTimeout(10, TimeUnit.MINUTES)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .cache(null)
    .apply { interceptors().removeAll { it is HttpLoggingInterceptor } }
    .build()
