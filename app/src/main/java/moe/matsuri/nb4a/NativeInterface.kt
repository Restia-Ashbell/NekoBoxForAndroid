package moe.matsuri.nb4a

import android.annotation.SuppressLint
import android.content.Context
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Build.VERSION_CODES
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.DefaultNetworkMonitor
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.security.KeyStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import libbox.InterfaceUpdateListener
import libbox.Libbox
import libbox.LocalDNSTransport
import libbox.NB4AInterface
import libbox.NetworkInterface as LibboxNetworkInterface
import libbox.NetworkInterfaceIterator
import libbox.Notification
import libbox.PlatformInterface
import libbox.StringIterator
import libbox.TunOptions
import libbox.WIFIState
import moe.matsuri.nb4a.net.LocalResolver
import moe.matsuri.nb4a.utils.LibcoreUtil

class NativeInterface : PlatformInterface, NB4AInterface {

    //  libbox interface

    override fun localDNSTransport(): LocalDNSTransport? {
        return LocalResolver
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean {
        return true
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        DataStore.vpnService?.protect(fd)
    }

    override fun openTun(tunOptions: TunOptions): Int {
        if (DataStore.vpnService == null) {
            throw Exception("no VpnService")
        }
        return DataStore.vpnService!!.startVpn(tunOptions)
    }

    override fun writeLog(message: String) {}

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProto: Int,
        srcIp: String,
        srcPort: Int,
        destIp: String,
        destPort: Int,
    ): Int {
        return SagerNet.connectivity.getConnectionOwnerUid(
            ipProto,
            InetSocketAddress(srcIp, srcPort),
            InetSocketAddress(destIp, destPort),
        )
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty())
            for (packageName in packageNames) {
                return packageName
            }

        error("unknown uid $uid")
    }

    override fun uidByPackageName(packageName: String): Int {
        PackageCache.awaitLoadSync()
        return PackageCache[packageName] ?: 0
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.setListener(null)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val networks = SagerNet.connectivity.allNetworks
        val networkInterfaces = NetworkInterface.getNetworkInterfaces().toList()
        val interfaces = mutableListOf<LibboxNetworkInterface>()
        for (network in networks) {
            val boxInterface = LibboxNetworkInterface()
            val linkProperties = SagerNet.connectivity.getLinkProperties(network) ?: continue
            val networkCapabilities =
                SagerNet.connectivity.getNetworkCapabilities(network) ?: continue
            boxInterface.name = linkProperties.interfaceName
            val networkInterface =
                networkInterfaces.find { it.name == boxInterface.name } ?: continue
            boxInterface.dnsServer =
                StringArray(linkProperties.dnsServers.mapNotNull { it.hostAddress }.iterator())
            boxInterface.type =
                when {
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                        Libbox.InterfaceTypeWIFI
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        Libbox.InterfaceTypeCellular
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                        Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
            boxInterface.index = networkInterface.index
            runCatching { boxInterface.mtu = networkInterface.mtu }
                .onFailure {
                    Log.e(
                        "PlatformInterface",
                        "failed to get mtu for interface ${boxInterface.name}",
                        it,
                    )
                }
            boxInterface.addresses =
                StringArray(
                    networkInterface.interfaceAddresses
                        .mapTo(mutableListOf()) { it.toPrefix() }
                        .iterator()
                )
            var dumpFlags = 0
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                dumpFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (networkInterface.isLoopback) {
                dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
            }
            if (networkInterface.isPointToPoint) {
                dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
            }
            if (networkInterface.supportsMulticast()) {
                dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
            }
            boxInterface.flags = dumpFlags
            boxInterface.metered =
                !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            interfaces.add(boxInterface)
        }
        return InterfaceArray(interfaces.iterator())
    }

    override fun underNetworkExtension(): Boolean {
        return false
    }

    override fun includeAllNetworks(): Boolean {
        return false
    }

    override fun clearDNSCache() {}

    // TODO
    override fun sendNotification(notification: Notification) {}

    // TODO: 'getter for connectionInfo: WifiInfo!' is deprecated
    override fun readWIFIState(): WIFIState? {
        val wifiManager =
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = wifiManager.connectionInfo
        return WIFIState(connectionInfo.ssid, connectionInfo.bssid)
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun systemCertificates(): StringIterator {
        val certificates = mutableListOf<String>()
        val keyStore = KeyStore.getInstance("AndroidCAStore")
        if (keyStore != null) {
            keyStore.load(null, null)
            val aliases = keyStore.aliases()
            while (aliases.hasMoreElements()) {
                val cert = keyStore.getCertificate(aliases.nextElement())
                certificates.add(
                    "-----BEGIN CERTIFICATE-----\n" +
                        Base64.encode(cert.encoded) +
                        "\n-----END CERTIFICATE-----"
                )
            }
        }
        return StringArray(certificates.iterator())
    }

    private class InterfaceArray(private val iterator: Iterator<LibboxNetworkInterface>) :
        NetworkInterfaceIterator {

        override fun hasNext(): Boolean {
            return iterator.hasNext()
        }

        override fun next(): LibboxNetworkInterface {
            return iterator.next()
        }
    }

    private class StringArray(private val iterator: Iterator<String>) : StringIterator {

        override fun len(): Int {
            // not used by core
            return 0
        }

        override fun hasNext(): Boolean {
            return iterator.hasNext()
        }

        override fun next(): String {
            return iterator.next()
        }
    }

    private fun InterfaceAddress.toPrefix(): String {
        return if (address is Inet6Address) {
            "${Inet6Address.getByAddress(address.address).hostAddress}/${networkPrefixLength}"
        } else {
            "${address.hostAddress}/${networkPrefixLength}"
        }
    }

    private val NetworkInterface.flags: Int
        @SuppressLint("SoonBlockedPrivateApi")
        get() {
            val getFlagsMethod = NetworkInterface::class.java.getDeclaredMethod("getFlags")
            return getFlagsMethod.invoke(this) as Int
        }

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        LibcoreUtil.resetAllConnections(true)
        DataStore.baseService?.apply {
            runOnDefaultDispatcher {
                val id =
                    data.proxy!!.config.profileTagMap.filterValues { it == tag }.keys.firstOrNull()
                        ?: -1
                val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
                // traffic & title
                data.proxy?.apply {
                    looper?.selectMain(id)
                    displayProfileName = ServiceNotification.genTitle(ent)
                    data.notification?.postNotificationTitle(displayProfileName)
                }
                // post binder
                data.binder.broadcast { b -> b.cbSelectorUpdate(id) }
            }
        }
    }
}
