package com.devilmedia.shield

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * A live WireGuard connection to the VPN server. Devil Shield hands it packets to
 * encrypt and send; it hands back packets that arrive. No Android code here, so the
 * automatic build can test it against a real WireGuard server.
 */
class WgTunnel(
    private val cfg: WgConfig,
    private val protect: (DatagramSocket) -> Unit,
    private val deliver: (ByteArray) -> Unit
) {
    enum class State { CONNECTING, CONNECTED, FAILED }

    companion object {
        @Volatile var state = State.CONNECTING
            private set
        @Volatile var lastHandshakeAt = 0L
            private set
        @Volatile var detail = ""
            private set
        @Volatile var active = false
            private set

        private const val REKEY_AFTER_MS = 120_000L
        private const val REJECT_AFTER_MS = 180_000L
        private const val RETRY_MS = 5_000L
    }

    private class Keypair(
        val send: ByteArray,
        val recv: ByteArray,
        val localIndex: Int,
        val remoteIndex: Int,
        val createdAt: Long
    ) {
        val sendCounter = AtomicLong(0)
        var replayMax = -1L
        var replayBits = 0L
    }

    private val staticPub = Wg.publicKey(cfg.privateKey)
    private val keepaliveMs = (if (cfg.keepalive > 0) cfg.keepalive else 25) * 1000L
    private val lock = Any()

    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var current: Keypair? = null
    @Volatile private var previous: Keypair? = null
    private var pending: Handshake? = null
    private var pendingIndex = 0
    private var pendingSentAt = 0L
    @Volatile private var lastSent = 0L
    private var startedAt = 0L

    private fun now() = System.nanoTime() / 1_000_000L

    fun start() {
        running = true
        active = true
        state = State.CONNECTING
        detail = "Connecting to ${cfg.endpointHost}…"
        lastHandshakeAt = 0L
        startedAt = now()
        Thread({ connectLoop() }, "wg-connect").start()
        Thread({ timerLoop() }, "wg-timer").start()
    }

    fun stop() {
        running = false
        active = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        current = null
        previous = null
    }

    val isConnected: Boolean get() = current != null

    /** Encrypts one packet from the device and sends it to the VPN server. */
    fun send(packet: ByteArray, len: Int) {
        val kp = current ?: return // not connected yet: drop, apps will retry
        val s = socket ?: return
        val padded = ((len + 15) / 16) * 16
        val plain = ByteArray(padded)
        System.arraycopy(packet, 0, plain, 0, len)
        val counter = kp.sendCounter.getAndIncrement()
        val sealed = Wg.seal(kp.send, counter, plain, 0, padded, null)
        val out = ByteArray(16 + sealed.size)
        out[0] = 4
        Wg.putLE32(out, 4, kp.remoteIndex)
        Wg.putLE64(out, 8, counter)
        System.arraycopy(sealed, 0, out, 16, sealed.size)
        try {
            s.send(DatagramPacket(out, out.size))
            lastSent = now()
        } catch (_: Exception) {
        }
    }

    private fun sendKeepalive() = send(ByteArray(0), 0)

    private fun connectLoop() {
        while (running) {
            try {
                val addr = InetAddress.getByName(cfg.endpointHost)
                val s = DatagramSocket()
                protect(s)
                s.connect(addr, cfg.endpointPort)
                s.soTimeout = 1000
                socket = s
                receiveLoop(s)
            } catch (e: Exception) {
                if (!running) return
                state = State.FAILED
                detail = "Can't reach ${cfg.endpointHost}. Check your internet connection."
            }
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
            current = null
            if (running) Thread.sleep(RETRY_MS)
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(65535)
        while (running && !s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val n = p.length
            if (n < 4) continue
            when (buf[0].toInt()) {
                2 -> if (n == 92) onResponse(buf.copyOf(92))
                4 -> if (n >= 32) onData(buf, n)
                // 3 = "server busy" cookie reply: the handshake is simply retried.
            }
        }
    }

    private fun timerLoop() {
        while (running) {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return
            }
            val t = now()
            val cur = current
            synchronized(lock) {
                val needKeys = cur == null || t - cur.createdAt > REKEY_AFTER_MS
                if (needKeys && (pending == null || t - pendingSentAt > RETRY_MS)) sendInitiation(t)
            }
            if (cur != null && t - cur.createdAt > REJECT_AFTER_MS) {
                current = null
                state = State.CONNECTING
            }
            if (current != null && t - lastSent > keepaliveMs) sendKeepalive()
            if (current == null && lastHandshakeAt == 0L && t - startedAt > 30_000L && socket != null) {
                state = State.FAILED
                detail = "No reply from the VPN server. The VPN file may be out of date. Try downloading a new one."
            }
        }
    }

    private fun sendInitiation(t: Long) {
        val s = socket ?: return
        val idx = Wg.randomIndex()
        val hs = Handshake(cfg.privateKey, staticPub, cfg.peerPublicKey, cfg.presharedKey)
        val msg = hs.initiation(idx)
        pending = hs
        pendingIndex = idx
        pendingSentAt = t
        try {
            s.send(DatagramPacket(msg, msg.size))
        } catch (_: Exception) {
        }
    }

    private fun onResponse(msg: ByteArray) {
        synchronized(lock) {
            val hs = pending ?: return
            if (Wg.getLE32(msg, 8) != pendingIndex) return
            val keys = hs.consumeResponse(msg) ?: return
            previous = current
            current = Keypair(keys.send, keys.recv, pendingIndex, keys.remoteIndex, now())
            pending = null
        }
        lastHandshakeAt = System.currentTimeMillis()
        state = State.CONNECTED
        detail = ""
        sendKeepalive() // lets the server start using the new keys
    }

    private fun onData(buf: ByteArray, n: Int) {
        val idx = Wg.getLE32(buf, 4)
        val counter = Wg.getLE64(buf, 8)
        val cur = current
        val prev = previous
        val kp = when {
            cur != null && cur.localIndex == idx -> cur
            prev != null && prev.localIndex == idx -> prev
            else -> return
        }
        if (!replayOk(kp, counter)) return
        val plain = Wg.open(kp.recv, counter, buf, 16, n - 16, null) ?: return
        markReplay(kp, counter)
        if (plain.isEmpty()) return // keepalive
        val len = ipLength(plain)
        if (len <= 0 || len > plain.size) return
        deliver(if (len == plain.size) plain else plain.copyOf(len))
    }

    private fun replayOk(kp: Keypair, c: Long): Boolean {
        if (c > kp.replayMax) return true
        val diff = kp.replayMax - c
        if (diff >= 64) return false
        return ((kp.replayBits ushr diff.toInt()) and 1L) == 0L
    }

    private fun markReplay(kp: Keypair, c: Long) {
        if (c > kp.replayMax) {
            val shift = c - kp.replayMax
            kp.replayBits = if (shift >= 64) 1L else (kp.replayBits shl shift.toInt()) or 1L
            kp.replayMax = c
        } else {
            kp.replayBits = kp.replayBits or (1L shl (kp.replayMax - c).toInt())
        }
    }

    private fun ipLength(p: ByteArray): Int {
        if (p.isEmpty()) return -1
        return when ((p[0].toInt() shr 4) and 0xF) {
            4 -> if (p.size >= 20) ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF) else -1
            6 -> if (p.size >= 40) 40 + (((p[4].toInt() and 0xFF) shl 8) or (p[5].toInt() and 0xFF)) else -1
            else -> -1
        }
    }
}
