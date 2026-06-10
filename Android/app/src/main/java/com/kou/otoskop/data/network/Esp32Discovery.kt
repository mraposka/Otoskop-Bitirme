package com.kou.otoskop.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * ESP32'yi yerel ağda mDNS / NSD (Network Service Discovery) ile bulur.
 *
 * Firmware tarafında ESP, kendini "otoskop" adıyla `_http._tcp` servisi olarak
 * yayınlar (`MDNS.addService("http","tcp",80)`). Bu sayede DHCP ile IP değişse
 * bile uygulama cihazı bulur; kullanıcının elle IP girmesine gerek kalmaz.
 *
 * [discover] çözümlenmiş IP'yi döndürür; süre dolarsa / bulunamazsa null.
 */
class Esp32Discovery(context: Context) {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    suspend fun discover(
        targetName: String = DEFAULT_NAME,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): String? {
        var activeListener: NsdManager.DiscoveryListener? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val resolving = AtomicBoolean(false)

                    val resolveListener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            resolving.set(false)
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            // Zone id (%wlan0) varsa at; IPv6 link-local'i kullanışsız yapar
                            val host = info.host?.hostAddress?.substringBefore('%')
                            if (!host.isNullOrBlank() && cont.isActive) {
                                cont.resume(host)
                            } else {
                                resolving.set(false)
                            }
                        }
                    }

                    val discoveryListener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) {}

                        override fun onServiceFound(info: NsdServiceInfo) {
                            val match = info.serviceName.contains(targetName, ignoreCase = true)
                            if (match && resolving.compareAndSet(false, true)) {
                                try {
                                    nsd.resolveService(info, resolveListener)
                                } catch (_: Exception) {
                                    resolving.set(false)
                                }
                            }
                        }

                        override fun onServiceLost(info: NsdServiceInfo) {}
                        override fun onDiscoveryStopped(serviceType: String) {}

                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    }
                    activeListener = discoveryListener

                    try {
                        nsd.discoverServices(
                            SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener,
                        )
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } finally {
            activeListener?.let {
                try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_http._tcp."
        const val DEFAULT_NAME = "otoskop"
        const val DEFAULT_TIMEOUT_MS = 6000L
    }
}
