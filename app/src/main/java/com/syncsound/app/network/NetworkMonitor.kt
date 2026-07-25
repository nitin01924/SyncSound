package com.syncsound.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.syncsound.app.logging.AppLog
import com.syncsound.app.logging.LogArea
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Observes default-network replacement so screens can explain outages while
 * socket reconnection proceeds independently.
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _available = MutableStateFlow(isUsable(connectivity.activeNetwork))
    val available: StateFlow<Boolean> = _available

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _available.value = isUsable(network)
            AppLog.i(LogArea.NETWORK, "network_available", "network" to network)
        }

        override fun onLost(network: Network) {
            _available.value = isUsable(connectivity.activeNetwork)
            AppLog.w(LogArea.NETWORK, "network_lost", null, "network" to network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _available.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            } else {
                true
            }
        }
    }

    init {
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onFailure { AppLog.w(LogArea.NETWORK, "network_monitor_unavailable", it) }
    }

    private fun isUsable(network: Network?): Boolean {
        val capabilities = network?.let(connectivity::getNetworkCapabilities) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        } else {
            true
        }
    }

    fun close() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }
}
