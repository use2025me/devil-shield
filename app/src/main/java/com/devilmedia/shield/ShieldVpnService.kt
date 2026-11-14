package com.devilmedia.shield

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
    }

    private var tun: ParcelFileDescriptor? = null
    private var output: FileOutputStream? = null
    private var workers: ExecutorService? = null
    private var scheduler: ScheduledExecutorService? = null
    private val appNames = ConcurrentHashMap<Int, Pair<String, String>>()

    @Volatile private var upstreamCache: List<InetAddress> = emptyList()
    @Volatile private var upstreamAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs(this).enabled = false
            shutdown()
            stopSelf()
            return START_NOT_STICKY
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

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32) // only lookups come through Devil Shield
            .setBlocking(true)
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
        workers = Executors.newFixedThreadPool(8)
        running = true
        Thread({ readLoop(fd) }, "shield-reader").start()

        val s = Executors.newSingleThreadScheduledExecutor()
        scheduler = s
        s.execute {
            Blocklists.loadCached(this)
            Blocklists.updateIfStale(this)
        }
        s.scheduleWithFixedDelay({ Blocklists.updateIfStale(this) }, 6, 6, TimeUnit.HOURS)
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val q = Packets.parseUdpDns(buf, n) ?: continue
                val pool = workers ?: break
                try {
                    pool.execute { handle(q) }
                } catch (_: RejectedExecutionException) {
                    break
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

    /** Passes an allowed lookup to the network's normal lookup service. */
    private fun forward(q: Packets.Query) {
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

    /** The lookup servers of the Wi-Fi or network the device is on, then public fallbacks. */
    private fun upstreams(): List<InetAddress> {
        val now = SystemClock.elapsedRealtime()
        if (upstreamCache.isNotEmpty() && now - upstreamAt < 60_000) return upstreamCache
        val list = ArrayList<InetAddress>()
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp?.dnsServers?.forEach {
                if (it is Inet4Address && it.hostAddress != VPN_DNS) list.add(it)
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
