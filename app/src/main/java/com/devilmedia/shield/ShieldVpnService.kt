package com.devilmedia.shield

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import androidx.core.content.ContextCompat

/**
 * The shield. It runs as a local VPN on the device itself (nothing leaves through a
 * remote server). Only website lookups are routed through it: each one is checked
 * against the protection lists, known threats get a "doesn't exist" answer, and
 * everything else is passed to the network's normal lookup service unchanged.
 */
class ShieldVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.devilmedia.shield.START"
        const val ACTION_STOP = "com.devilmedia.shield.STOP"
        const val ACTION_RESTART = "com.devilmedia.shield.RESTART"

        @Volatile var running = false
            private set
        /** True if another VPN app took over. */
        @Volatile var revoked = false
            private set

        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VPN_DNS = "10.111.222.2"
        // Used only if the network's own lookup service can't be found.
        private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")

        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, ShieldVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ShieldVpnService::class.java).setAction(ACTION_STOP))
        }

        /** Applies VPN changes straight away if protection is on. */
        fun restartIfRunning(ctx: Context) {
            if (!running) return
            try {
                ctx.startService(Intent(ctx, ShieldVpnService::class.java).setAction(ACTION_RESTART))
            } catch (_: Exception) {
            }
        }

        /** True while protection is on and carrying the person's own VPN. */
        val vpnMode: Boolean get() = running && WgTunnel.active
    }

    private var tun: ParcelFileDescriptor? = null
    private var output: FileOutputStream? = null
    private var workers: ExecutorService? = null
    private var scheduler: ScheduledExecutorService? = null
    private val appNames = ConcurrentHashMap<Int, Pair<String, String>>()

    // Checks each newly installed app (Android only tells running apps about installs).
    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return // just an update
            val pkg = intent.data?.schemeSpecificPart ?: return
            val pool = workers ?: return
            try {
                pool.execute { NewAppCheck.check(this@ShieldVpnService, pkg) }
            } catch (_: Exception) {
            }
        }
    }
    private var installReceiverOn = false

    // Built-in VPN (when the person has imported a VPN file and switched it on).
    @Volatile private var tunnel: WgTunnel? = null
    private var tunnelDns = byteArrayOf(1, 1, 1, 1)
    private val vpnDnsBytes = InetAddress.getByName(VPN_DNS).address
    private val dnsPorts = ConcurrentHashMap<Int, Long>()

    @Volatile private var upstreamCache: List<InetAddress> = emptyList()
    @Volatile private var upstreamAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs(this).enabled = false
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) {
            shutdown()
            startShield()
            return START_STICKY
        }
        // ACTION_START, or Android starting us for "Always-on VPN".
        Prefs(this).enabled = true
        startShield()
        return START_STICKY
    }

    @Synchronized private fun startShield() {
        if (tun != null) return
        revoked = false
        ActivityLog.load(this)
        Blocklists.refreshSettings(this)

        val prefs = Prefs(this)
        val vpn = if (prefs.vpnOn) WgConfig.parse(prefs.vpnConfig) else null

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addDnsServer(VPN_DNS)
            .setBlocking(true)
        if (vpn == null) {
            // Protection only: just website lookups come through Devil Shield.
            builder.addAddress(VPN_ADDRESS, 32)
            builder.addRoute(VPN_DNS, 32)
        } else {
            // Protection + VPN: everything goes through Devil Shield and on to the VPN server.
            for ((addr, prefix) in vpn.addresses) builder.addAddress(addr, prefix)
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0) // newer-style traffic too, so nothing leaks around the VPN
            builder.setMtu(vpn.mtu)
            tunnelDns = vpn.dns.firstOrNull { it.address.size == 4 }?.address ?: byteArrayOf(1, 1, 1, 1)
        }
        try {
            builder.addDisallowedApplication(packageName) // our own list downloads go direct
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
        )

        val fd = try {
            builder.establish()
        } catch (_: Exception) {
            null
        }
        if (fd == null) {
            running = false
            stopSelf()
            return
        }

        tun = fd
        output = FileOutputStream(fd.fileDescriptor)
        workers = Executors.newFixedThreadPool(16)
        running = true
        if (vpn != null) {
            val t = WgTunnel(vpn, protect = { protect(it) }, deliver = { deliverFromTunnel(it) })
            tunnel = t
            t.start()
        }
        Thread({ readLoop(fd) }, "shield-reader").start()
        StatusViews.refresh(this)

        try {
            val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
            ContextCompat.registerReceiver(this, installReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            installReceiverOn = true
        } catch (_: Exception) {
        }

        val s = Executors.newSingleThreadScheduledExecutor()
        scheduler = s
        s.execute {
            Blocklists.loadCached(this)
            Blocklists.updateIfStale(this)
        }
        // Checks every 15 minutes; it only downloads when the lists are missing or a day old.
        s.scheduleWithFixedDelay({
            Blocklists.updateIfStale(this)
            WeeklySummary.maybeSend(this)
        }, 15, 15, TimeUnit.MINUTES)
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val t = tunnel
                val q = Packets.parseUdpDns(buf, n)
                if (q != null && (t == null || q.dstIp.contentEquals(vpnDnsBytes))) {
                    // A website lookup: check it against the protection lists.
                    val pool = workers ?: break
                    try {
                        pool.execute { handle(q) }
                    } catch (_: RejectedExecutionException) {
                        break
                    }
                } else if (t != null) {
                    // Everything else goes straight into the VPN.
                    t.send(buf, n)
                }
            }
        } catch (_: Exception) {
            // The tunnel was closed (protection turned off).
        }
    }

    private fun handle(q: Packets.Query) {
        val question = Packets.question(q.dns)
        if (question == null) {
            forward(q)
            return
        }
        val (name, end) = question
        ActivityLog.recordCheck()
        val verdict = Blocklists.check(name)
        if (verdict == Blocklists.Verdict.ALLOW) {
            forward(q)
            return
        }
        // Work out which app asked before answering, while its connection still exists.
        val (app, pkg) = ownerOf(q)
        write(Packets.reply(q, Packets.blockedAnswer(q.dns, end)))
        ActivityLog.recordBlock(
            this,
            LogEntry(System.currentTimeMillis(), name, app, pkg, verdict == Blocklists.Verdict.FAMILY)
        )
    }

    /** Passes an allowed lookup on: encrypted if private lookups are on, else the network's own service. */
    private fun forward(q: Packets.Query) {
        val t = tunnel
        if (t != null) {
            // With the VPN on, allowed lookups go privately through the VPN to its own lookup service.
            if (dnsPorts.size > 4096) dnsPorts.clear()
            dnsPorts[q.srcPort] = SystemClock.elapsedRealtime()
            val pkt = Packets.udp4(q.srcIp, tunnelDns, q.srcPort, 53, q.dns)
            t.send(pkt, pkt.size)
            return
        }
        if (Prefs(this).encryptedDns) {
            val answer = Doh.query(q.dns)
            if (answer != null) {
                write(Packets.reply(q, answer))
                return
            }
        }
        for (server in upstreams()) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 4000
                    s.send(DatagramPacket(q.dns, q.dns.size, server, 53))
                    val rb = ByteArray(4096)
                    val rp = DatagramPacket(rb, rb.size)
                    s.receive(rp)
                    write(Packets.reply(q, rb.copyOf(rp.length)))
                }
                return
            } catch (_: Exception) {
                // Try the next server.
            }
        }
    }

    /** A packet arriving from the VPN server, on its way to an app. */
    private fun deliverFromTunnel(pkt: ByteArray) {
        val port = Packets.dnsAnswerPort(pkt, tunnelDns)
        if (port >= 0 && dnsPorts.remove(port) != null) {
            // An answer to a lookup Devil Shield passed on: make it come from Devil Shield's address.
            Packets.rewriteSource(pkt, vpnDnsBytes)
        }
        write(pkt)
    }

    /** The lookup servers of the Wi-Fi or network the device is on, then public fallbacks. */
    private fun upstreams(): List<InetAddress> {
        val now = SystemClock.elapsedRealtime()
        if (upstreamCache.isNotEmpty() && now - upstreamAt < 60_000) return upstreamCache
        val list = ArrayList<InetAddress>()
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp?.dnsServers?.forEach {
                // Both older (IPv4) and newer (IPv6) addresses, so it works on every kind of network.
                if (it.hostAddress != VPN_DNS) list.add(it)
            }
        } catch (_: Exception) {
        }
        for (f in FALLBACK_DNS) {
            val a = InetAddress.getByName(f)
            if (a !in list) list.add(a)
        }
        upstreamCache = list
        upstreamAt = now
        return list
    }

    private fun ownerOf(q: Packets.Query): Pair<String, String> {
        if (Build.VERSION.SDK_INT < 29) return Pair(AppInfo.UNKNOWN, "")
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val uid = cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(q.srcIp), q.srcPort),
                InetSocketAddress(InetAddress.getByAddress(q.dstIp), q.dstPort)
            )
            if (uid < 0) Pair(AppInfo.UNKNOWN, "")
            else appNames.getOrPut(uid) { AppInfo.forUid(this, uid) }
        } catch (_: Exception) {
            Pair(AppInfo.UNKNOWN, "")
        }
    }

    private fun write(packet: ByteArray) {
        val o = output ?: return
        synchronized(o) {
            try {
                o.write(packet)
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized private fun shutdown() {
        running = false
        tunnel?.stop()
        tunnel = null
        dnsPorts.clear()
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        output = null
        workers?.shutdownNow()
        workers = null
        scheduler?.shutdownNow()
        scheduler = null
        if (installReceiverOn) {
            try {
                unregisterReceiver(installReceiver)
            } catch (_: Exception) {
            }
            installReceiverOn = false
        }
        StatusViews.refresh(this)
        ActivityLog.save(this)
    }

    override fun onRevoke() {
        revoked = true
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
