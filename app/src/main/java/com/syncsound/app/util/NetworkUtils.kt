package com.syncsound.app.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds the active private IPv4 address for display and manual joining.
 */
object NetworkUtils {
    fun localIpv4Address(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull() ?: "Unavailable"
}
