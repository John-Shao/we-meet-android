package com.we.meet.data.auth

import java.io.IOException
import okhttp3.Request

/** Local request tag only: never send a login session ID over HTTP. */
data class PrivateLogin(val session: String) {
    override fun toString() = "PrivateLogin(<private>)"
}

internal fun requirePrivateLogin(request: Request, currentSession: String) {
    request.tag(PrivateLogin::class.java)?.let {
        if (it.session != currentSession) throw IOException("authentication_changed")
    }
}
