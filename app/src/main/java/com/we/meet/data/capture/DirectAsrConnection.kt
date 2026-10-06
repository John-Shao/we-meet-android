package com.we.meet.data.capture

import com.we.meet.data.api.DirectAsrCredentials
import java.io.Closeable

interface DirectAsrConnection : Closeable {
    suspend fun start(credentials: DirectAsrCredentials)
    fun send(pcm: ByteArray): Boolean
    suspend fun finish(): List<DirectAsrRow>
}
