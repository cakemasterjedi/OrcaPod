package org.orcapod.app.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.orcapod.app.printhost.DiscoveredPrinter
import org.orcapod.app.printhost.HostType
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.ArrayDeque

/**
 * Finds printers on the Wi-Fi network: Bambu printers by their UDP
 * announcements, OctoPrint and Moonraker by their mDNS services. Each find
 * goes to [onFound]; the same printer may be reported more than once.
 */
class PrinterDiscovery(context: Context, private val onFound: (DiscoveredPrinter) -> Unit) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var bambuJob: Job? = null
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    // NsdManager resolves one service at a time before Android 14.
    private val resolveQueue = ArrayDeque<Pair<NsdServiceInfo, HostType>>()
    private var resolving = false

    fun start(scope: CoroutineScope) {
        stop()
        lock = wifi.createMulticastLock("orcapod-discovery").apply { setReferenceCounted(false); acquire() }
        bambuJob = scope.launch(Dispatchers.IO) { listenBambu() }
        for ((service, type) in SERVICES) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) = enqueue(info, type)
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            runCatching { nsd.discoverServices(service, NsdManager.PROTOCOL_DNS_SD, listener) }.onSuccess { listeners += listener }
        }
    }

    fun stop() {
        bambuJob?.cancel()
        bambuJob = null
        for (l in listeners) runCatching { nsd.stopServiceDiscovery(l) }
        listeners.clear()
        synchronized(resolveQueue) { resolveQueue.clear() }
        lock?.let { runCatching { it.release() } }
        lock = null
    }

    private fun CoroutineScope.listenBambu() {
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1000
                bind(InetSocketAddress(BAMBU_PORT))
            }
        } catch (_: java.io.IOException) {
            return
        }
        socket.use {
            val buf = ByteArray(2048)
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    it.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: java.io.IOException) {
                    return
                }
                DiscoveredPrinter.parseBambuNotify(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let(onFound)
            }
        }
    }

    private fun enqueue(info: NsdServiceInfo, type: HostType) {
        synchronized(resolveQueue) {
            resolveQueue.add(info to type)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    @Suppress("DEPRECATION") // resolveService is the only resolve call before Android 14.
    private fun resolveNext() {
        val (info, type) = synchronized(resolveQueue) {
            resolveQueue.poll() ?: run { resolving = false; return }
        }
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = resolveNext()
            override fun onServiceResolved(s: NsdServiceInfo) {
                val address = s.host?.hostAddress
                if (address != null) {
                    val host = if (':' in address) "[$address]" else address
                    val port = if (s.port == 80 || s.port <= 0) "" else ":${s.port}"
                    onFound(DiscoveredPrinter(s.serviceName, type, host + port))
                }
                resolveNext()
            }
        })
    }

    companion object {
        const val BAMBU_PORT = 2021
        private val SERVICES = listOf("_octoprint._tcp" to HostType.OCTOPRINT, "_moonraker._tcp" to HostType.MOONRAKER)
    }
}
