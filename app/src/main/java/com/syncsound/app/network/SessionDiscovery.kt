package com.syncsound.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.syncsound.app.logging.AppLog
import com.syncsound.app.logging.LogArea
import com.syncsound.app.model.DiscoveredSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface SessionDiscovery {
    val sessions: StateFlow<List<DiscoveredSession>>
    fun advertise(name: String, port: Int)
    fun stopAdvertising()
    fun startDiscovery()
    fun stopDiscovery()
}

/**
 * Wraps Android DNS-SD and owns the multicast lock needed by some Android and
 * hotspot combinations. Manual IP joining remains available when mDNS is blocked.
 */
class NsdSessionDiscovery(context: Context) : SessionDiscovery {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val found = linkedMapOf<String, DiscoveredSession>()
    private val _sessions = MutableStateFlow<List<DiscoveredSession>>(emptyList())
    override val sessions: StateFlow<List<DiscoveredSession>> = _sessions

    override fun advertise(name: String, port: Int) {
        stopAdvertising()
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                AppLog.i(LogArea.DISCOVERY, "service_registered", "name" to serviceInfo.serviceName)
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                AppLog.e(LogArea.DISCOVERY, "service_registration_failed", null, "code" to errorCode)
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                AppLog.i(LogArea.DISCOVERY, "service_unregistered")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                AppLog.w(LogArea.DISCOVERY, "service_unregistration_failed", null, "code" to errorCode)
            }
        }
        registrationListener = listener
        runCatching {
            nsd.registerService(
                NsdServiceInfo().apply {
                    serviceName = name
                    serviceType = Protocol.SERVICE_TYPE
                    setPort(port)
                },
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
        }.onFailure { AppLog.e(LogArea.DISCOVERY, "advertise_failed", it) }
    }

    override fun stopAdvertising() {
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        registrationListener = null
    }

    override fun startDiscovery() {
        if (discoveryListener != null) return
        multicastLock = runCatching {
            wifi.createMulticastLock("syncsound-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { AppLog.w(LogArea.DISCOVERY, "multicast_lock_failed", it) }.getOrNull()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                AppLog.i(LogArea.DISCOVERY, "discovery_started")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                AppLog.i(LogArea.DISCOVERY, "discovery_stopped")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = stopDiscovery()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = stopDiscovery()
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                found.entries.removeAll { it.value.name == serviceInfo.serviceName }
                _sessions.value = found.values.toList()
                AppLog.i(LogArea.DISCOVERY, "service_lost", "name" to serviceInfo.serviceName)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != Protocol.SERVICE_TYPE) return
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = info.host ?: return
                        val code = Regex("""\b\d{6}\b""")
                            .find(info.serviceName)?.value.orEmpty()
                        val session = DiscoveredSession(
                            info.serviceName,
                            host,
                            info.port,
                            sessionCode = code,
                        )
                        found[session.key] = session
                        _sessions.value = found.values.toList()
                        AppLog.i(
                            LogArea.DISCOVERY,
                            "service_resolved",
                            "name" to session.name,
                            "address" to host.hostAddress,
                        )
                    }
                })
            }
        }
        discoveryListener = listener
        runCatching {
            nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            AppLog.e(LogArea.DISCOVERY, "discovery_failed", it)
            stopDiscovery()
        }
    }

    override fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        found.clear()
        _sessions.value = emptyList()
    }
}
