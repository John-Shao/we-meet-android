package com.we.meet.feature.docs.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Collected only while the owning docs screen is resumed. */
fun docsConnectivity(context: Context) = callbackFlow {
    val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    fun report() {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        // Corporate networks may reach Docs while Android's public validation
        // endpoint is blocked. Let the request/backoff decide reachability.
        trySend(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = report()
        override fun onLost(network: Network) = report()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = report()
    }
    manager.registerDefaultNetworkCallback(callback)
    report()
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
