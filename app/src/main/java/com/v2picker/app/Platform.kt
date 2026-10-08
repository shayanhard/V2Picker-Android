package com.v2picker.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import android.util.Base64
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.security.KeyStore
import io.nekohasekai.libbox.NetworkInterface as BoxIface

/**
 * Bridge between sing-box (libbox) and Android.
 * Written against libbox v1.12.x. If you build a different sing-box version and the compiler says
 * "overrides nothing" / "is not abstract", copy the method list from sing-box-for-android's
 * PlatformInterfaceWrapper.kt at that same tag: this is the only file that depends on it.
 */
open class Platform(private val ctx: Context, private val vpn: BoxVpnService?) : PlatformInterface {

    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val callbacks = HashMap<InterfaceUpdateListener, ConnectivityManager.NetworkCallback>()

    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun usePlatformAutoDetectInterfaceControl() = true
    override fun autoDetectInterfaceControl(fd: Int) { vpn?.protect(fd) }

    override fun openTun(options: TunOptions): Int =
        vpn?.openTun() ?: throw IllegalStateException("tun is only available inside the VPN service")

    override fun writeLog(message: String) = Core.log(message)
    override fun useProcFS() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int,
                                     destinationAddress: String, destinationPort: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Process.INVALID_UID
        return cm.getConnectionOwnerUid(ipProtocol, InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort))
    }

    override fun packageNameByUid(uid: Int): String =
        ctx.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: throw IllegalArgumentException("unknown uid $uid")

    override fun uidByPackageName(packageName: String): Int =
        ctx.packageManager.getApplicationInfo(packageName, 0).uid

    // ---- default network monitor (what auto_detect_interface needs on Android)
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = push(listener, network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = push(listener, network)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = push(listener, network)
            override fun onLost(network: Network) { listener.updateDefaultInterface("", -1, false, false) }
        }
        callbacks[listener] = cb
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) cm.requestNetwork(req, cb) else cm.registerNetworkCallback(req, cb)
        cm.activeNetwork?.let { push(listener, it) }
    }

    private fun push(listener: InterfaceUpdateListener, network: Network) {
        val lp = cm.getLinkProperties(network) ?: return
        val name = lp.interfaceName ?: return
        val caps = cm.getNetworkCapabilities(network)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return
        val index = runCatching { java.net.NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        runCatching { listener.updateDefaultInterface(name, index, expensive, false) }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        callbacks.remove(listener)?.let { runCatching { cm.unregisterNetworkCallback(it) } }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = mutableListOf<BoxIface>()
        for (net in cm.allNetworks) {
            val lp = cm.getLinkProperties(net) ?: continue
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val j = runCatching { java.net.NetworkInterface.getByName(lp.interfaceName) }.getOrNull() ?: continue
            list += BoxIface().apply {
                name = lp.interfaceName
                index = j.index
                mtu = runCatching { j.mtu }.getOrDefault(1500)
                addresses = StrIter(lp.linkAddresses.map { "${it.address.hostAddress?.substringBefore('%')}/${it.prefixLength}" })
                dnsServer = StrIter(lp.dnsServers.mapNotNull { it.hostAddress })
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox_WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox_CELLULAR
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox_ETHERNET
                    else -> Libbox_OTHER
                }
                flags = (if (j.isUp) 1 else 0) or (if (j.isLoopback) 4 else 0) or (if (j.supportsMulticast()) 16 else 0)
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        return object : NetworkInterfaceIterator {
            val it = list.iterator()
            override fun hasNext() = it.hasNext()
            override fun next(): BoxIface = it.next()
        }
    }

    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() {}
    override fun sendNotification(notification: Notification) {}

    override fun systemCertificates(): StringIterator {
        val pems = runCatching {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
            ks.aliases().toList().mapNotNull { a ->
                ks.getCertificate(a)?.encoded?.let {
                    "-----BEGIN CERTIFICATE-----\n" + Base64.encodeToString(it, Base64.NO_WRAP).chunked(64).joinToString("\n") +
                        "\n-----END CERTIFICATE-----"
                }
            }
        }.getOrDefault(emptyList())
        return StrIter(pems)
    }

    class StrIter(private val items: List<String>) : StringIterator {
        private var i = 0
        override fun len() = items.size
        override fun hasNext() = i < items.size
        override fun next() = items[i++]
    }

    companion object {
        // interface types as libbox numbers them (constants.go: InterfaceTypeWIFI = 0, Cellular = 1, Ethernet = 2, Other = 3)
        const val Libbox_WIFI = 0
        const val Libbox_CELLULAR = 1
        const val Libbox_ETHERNET = 2
        const val Libbox_OTHER = 3
        @Suppress("unused") private fun v6(a: java.net.InetAddress) = a is Inet6Address
    }
}
