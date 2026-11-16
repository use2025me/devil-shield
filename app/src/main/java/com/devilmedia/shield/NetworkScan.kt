package com.devilmedia.shield

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Looks at the devices on the person's own Wi-Fi or wired network and points out the
 * common risks: cameras, remote login left open, remote debugging on boxes, and so on.
 * It only checks whether a few well-known doors are open; it never tries to log in.
 */
object NetworkScan {

    class Device(
        val ip: String,
        val name: String,
        val ports: Set<Int>,
        val isRouter: Boolean,
        val isThisDevice: Boolean
    ) {
        val kind: String
            get() = when {
                isThisDevice -> "This device"
                isRouter -> "Your router"
                554 in ports || 8000 in ports || 37777 in ports -> "Camera or video recorder"
                8008 in ports || 8009 in ports -> "Smart TV or streaming device"
                5555 in ports -> "Android device"
                445 in ports -> "Computer or storage drive"
                else -> "Device with a web page"
            }

        /** (level, title, advice) notes for this device. */
        val notes: List<Triple<Level, String, String>>
            get() {
                val out = ArrayList<Triple<Level, String, String>>()
                if (23 in ports) out += Triple(
                    Level.ACTION, "Old remote login (Telnet) is open",
                    "This is a common way hackers take over cameras and routers. Turn Telnet off in the device's " +
                        "settings, or update its software. If you can't, ask the maker for help."
                )
                if (5555 in ports && !isThisDevice) out += Triple(
                    Level.ACTION, "Remote debugging (ADB) is open",
                    "Anyone on your Wi-Fi could control this device. On that box, go to Developer options and " +
                        "switch off ADB / network debugging."
                )
                if (554 in ports || 8000 in ports || 37777 in ports) out += Triple(
                    Level.WATCH, "Camera check",
                    "Make sure you've changed its default password, its software is up to date, and you view it " +
                        "away from home through the maker's app rather than by \"port forwarding\" on the router."
                )
                if (isRouter) out += Triple(
                    Level.WATCH, "Router check",
                    "Make sure the router's admin password isn't the one printed on it, and switch off UPnP and " +
                        "WPS in its settings if you don't need them. Keep its software up to date."
                )
                if (445 in ports && !isRouter) out += Triple(
                    Level.WATCH, "File sharing is on",
                    "Make sure shared folders need a password, so other people on the Wi-Fi can't open them."
                )
                return out
            }
    }

    class Result(val devices: List<Device>, val network: String)

    private val PORTS = intArrayOf(80, 443, 554, 8000, 37777, 23, 22, 5555, 8008, 8009, 8080, 445)

    /** Scans the local network. Returns null if the device isn't on Wi-Fi or a wired network. */
    fun scan(ctx: Context, progress: (Int, Int) -> Unit): Result? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) return null
        val lp = cm.getLinkProperties(network) ?: return null
        val me = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address } ?: return null
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
        val myIp = me.hostAddress ?: return null
        val base = me.address.copyOf()

        val pool = Executors.newFixedThreadPool(48)
        val done = AtomicInteger(0)
        val found = java.util.concurrent.ConcurrentHashMap<String, Set<Int>>()
        for (host in 1..254) {
            pool.execute {
                val addr = base.copyOf()
                addr[3] = host.toByte()
                val ip = InetAddress.getByAddress(addr)
                val open = HashSet<Int>()
                for (port in PORTS) {
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(ip, port), 300)
                            open.add(port)
                        }
                    } catch (_: Exception) {
                    }
                }
                val ipText = ip.hostAddress ?: ""
                if (open.isNotEmpty() || ipText == myIp || ip == gateway) found[ipText] = open
                progress(done.incrementAndGet(), 254)
            }
        }
        pool.shutdown()
        pool.awaitTermination(3, TimeUnit.MINUTES)

        // Friendly names where the router provides them.
        val namePool = Executors.newFixedThreadPool(16)
        val names = java.util.concurrent.ConcurrentHashMap<String, String>()
        for (ip in found.keys) {
            namePool.execute {
                try {
                    val n = InetAddress.getByName(ip).canonicalHostName
                    if (n != null && n != ip) names[ip] = n.removeSuffix(".lan").removeSuffix(".home").removeSuffix(".local")
                } catch (_: Exception) {
                }
            }
        }
        namePool.shutdown()
        namePool.awaitTermination(20, TimeUnit.SECONDS)

        val devices = found.map { (ip, ports) ->
            Device(ip, names[ip] ?: "", ports, gatewayMatches(ip, gateway), ip == myIp)
        }.sortedWith(compareBy<Device>({ -(it.notes.count { n -> n.first == Level.ACTION }) }, { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }))
        val net = "${base[0].toInt() and 0xFF}.${base[1].toInt() and 0xFF}.${base[2].toInt() and 0xFF}.x"
        return Result(devices, net)
    }

    private fun gatewayMatches(ip: String, gateway: InetAddress?): Boolean =
        gateway != null && gateway.hostAddress == ip
}
