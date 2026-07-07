package com.we.meet.push

import com.igexin.sdk.PushService

/**
 * Empty shell over Getui's [PushService] so the long-lived push process runs
 * under a component name we own (declared in AndroidManifest with
 * process=":pushservice"). No behaviour is added — Getui just needs the
 * concrete class registered.
 */
class WeMeetPushService : PushService()
