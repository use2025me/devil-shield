package com.devilmedia.shield

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Base64

/**
 * The WireGuard protocol (the connection method used by Surfshark, Proton VPN, Mullvad
 * and others), so Devil Shield can carry a VPN connection itself. Pure Kotlin, no
 * Android code, so it can be tested against a real WireGuard server.
 */
object Wg {
    val CONSTRUCTION = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s".toByteArray()
    val IDENTIFIER = "WireGuard v1 zx2c4 Jason@zx2c4.com".toByteArray()
    val LABEL_MAC1 = "mac1----".toByteArray()

    private val random = SecureRandom()

    fun hash(vararg parts: ByteArray): ByteArray {
        val d = Blake2sDigest(256)
        for (p in parts) d.update(p, 0, p.size)
        val out = ByteArray(32)
        d.doFinal(out, 0)
        return out
    }

    /** Keyed BLAKE2s with a 16-byte result (used for mac1). */
    fun mac(key: ByteArray, data: ByteArray, len: Int): ByteArray {
        val d = Blake2sDigest(key, 16, null, null)
        d.update(data, 0, len)
        val out = ByteArray(16)
        d.doFinal(out, 0)
        return out
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val h = HMac(Blake2sDigest(256))
        h.init(KeyParameter(key))
        for (p in parts) h.update(p, 0, p.size)
        val out = ByteArray(32)
        h.doFinal(out, 0)
        return out
    }

    /** WireGuard's key derivation: returns [n] 32-byte values. */
    fun kdf(key: ByteArray, input: ByteArray, n: Int): List<ByteArray> {
        val t0 = hmac(key, input)
        val out = ArrayList<ByteArray>(n)
        var prev = ByteArray(0)
        for (i in 1..n) {
            prev = hmac(t0, prev, byteArrayOf(i.toByte()))
            out.add(prev)
        }
        return out
    }

    private fun nonce(counter: Long): ByteArray {
        val n = ByteArray(12)
        putLE64(n, 4, counter)
        return n
    }

    fun seal(key: ByteArray, counter: Long, plain: ByteArray, off: Int, len: Int, aad: ByteArray?): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce(counter), aad))
        val out = ByteArray(c.getOutputSize(len))
        var n = c.processBytes(plain, off, len, out, 0)
        n += c.doFinal(out, n)
        return if (n == out.size) out else out.copyOf(n)
    }

    fun open(key: ByteArray, counter: Long, sealed: ByteArray, off: Int, len: Int, aad: ByteArray?): ByteArray? {
        if (len < 16) return null
        return try {
            val c = ChaCha20Poly1305()
            c.init(false, AEADParameters(KeyParameter(key), 128, nonce(counter), aad))
            val out = ByteArray(c.getOutputSize(len))
            var n = c.processBytes(sealed, off, len, out, 0)
            n += c.doFinal(out, n)
            if (n == out.size) out else out.copyOf(n)
        } catch (_: InvalidCipherTextException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    fun newPrivateKey(): ByteArray {
        val k = ByteArray(32)
        random.nextBytes(k)
        return k
    }

    fun publicKey(priv: ByteArray): ByteArray {
        val out = ByteArray(32)
        X25519.scalarMultBase(priv, 0, out, 0)
        return out
    }

    fun dh(priv: ByteArray, pub: ByteArray): ByteArray {
        val out = ByteArray(32)
        X25519.scalarMult(priv, 0, pub, 0, out, 0)
        return out
    }

    /** TAI64N timestamp: always increasing, so the server can reject replayed handshakes. */
    fun tai64n(): ByteArray {
        val nowNanos = System.currentTimeMillis() * 1_000_000L + (System.nanoTime() % 1_000_000L).let { if (it < 0) -it else it }
        val secs = 0x400000000000000aL + nowNanos / 1_000_000_000L
        val nanos = (nowNanos % 1_000_000_000L).toInt()
        val out = ByteArray(12)
        for (i in 0 until 8) out[i] = (secs ushr (56 - 8 * i)).toByte()
        for (i in 0 until 4) out[8 + i] = (nanos ushr (24 - 8 * i)).toByte()
        return out
    }

    fun randomIndex(): Int = random.nextInt()

    fun putLE32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }

    fun getLE32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    fun putLE64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = (v ushr (8 * i)).toByte()
    }

    fun getLE64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
}

/** One WireGuard handshake, from Devil Shield's side (the initiator). */
class Handshake(
    private val staticPriv: ByteArray,
    private val staticPub: ByteArray,
    private val peerPub: ByteArray,
    private val psk: ByteArray
) {
    class Keys(val send: ByteArray, val recv: ByteArray, val remoteIndex: Int)

    private var ck = ByteArray(32)
    private var h = ByteArray(32)
    private var ePriv = ByteArray(32)

    /** Builds the 148-byte "hello" message. */
    fun initiation(localIndex: Int): ByteArray {
        ck = Wg.hash(Wg.CONSTRUCTION)
        h = Wg.hash(ck, Wg.IDENTIFIER)
        h = Wg.hash(h, peerPub)

        ePriv = Wg.newPrivateKey()
        val ePub = Wg.publicKey(ePriv)
        ck = Wg.kdf(ck, ePub, 1)[0]
        h = Wg.hash(h, ePub)

        var r = Wg.kdf(ck, Wg.dh(ePriv, peerPub), 2)
        ck = r[0]
        val encStatic = Wg.seal(r[1], 0, staticPub, 0, 32, h)
        h = Wg.hash(h, encStatic)

        r = Wg.kdf(ck, Wg.dh(staticPriv, peerPub), 2)
        ck = r[0]
        val ts = Wg.tai64n()
        val encTs = Wg.seal(r[1], 0, ts, 0, ts.size, h)
        h = Wg.hash(h, encTs)

        val msg = ByteArray(148)
        msg[0] = 1
        Wg.putLE32(msg, 4, localIndex)
        System.arraycopy(ePub, 0, msg, 8, 32)
        System.arraycopy(encStatic, 0, msg, 40, 48)
        System.arraycopy(encTs, 0, msg, 88, 28)
        val mac1 = Wg.mac(Wg.hash(Wg.LABEL_MAC1, peerPub), msg, 116)
        System.arraycopy(mac1, 0, msg, 116, 16)
        // mac2 (bytes 132-147) stays zero: only needed when a server is under heavy load.
        return msg
    }

    /** Reads the server's 92-byte reply. Returns the session keys, or null if it doesn't check out. */
    fun consumeResponse(msg: ByteArray): Keys? {
        if (msg.size < 92 || msg[0].toInt() != 2) return null
        val ePubR = msg.copyOfRange(12, 44)
        ck = Wg.kdf(ck, ePubR, 1)[0]
        h = Wg.hash(h, ePubR)
        ck = Wg.kdf(ck, Wg.dh(ePriv, ePubR), 1)[0]
        ck = Wg.kdf(ck, Wg.dh(staticPriv, ePubR), 1)[0]
        val r = Wg.kdf(ck, psk, 3)
        ck = r[0]
        h = Wg.hash(h, r[1])
        val emptySealed = msg.copyOfRange(44, 60)
        Wg.open(r[2], 0, emptySealed, 0, 16, h) ?: return null
        h = Wg.hash(h, emptySealed)
        val keys = Wg.kdf(ck, ByteArray(0), 2)
        return Keys(send = keys[0], recv = keys[1], remoteIndex = Wg.getLE32(msg, 4))
    }
}

/** A WireGuard VPN file (.conf), as given by Surfshark and other VPN providers. */
class WgConfig(
    val privateKey: ByteArray,
    val addresses: List<Pair<InetAddress, Int>>,
    val dns: List<InetAddress>,
    val mtu: Int,
    val peerPublicKey: ByteArray,
    val presharedKey: ByteArray,
    val endpointHost: String,
    val endpointPort: Int,
    val keepalive: Int
) {
    companion object {
        fun parse(text: String): WgConfig? {
            try {
                var section = ""
                val iface = HashMap<String, String>()
                val peer = HashMap<String, String>()
                var peers = 0
                for (raw in text.lines()) {
                    val line = raw.substringBefore('#').trim()
                    if (line.isEmpty()) continue
                    if (line.startsWith("[")) {
                        section = line.lowercase()
                        if (section == "[peer]") peers++
                        continue
                    }
                    val eq = line.indexOf('=')
                    if (eq < 0) continue
                    val k = line.substring(0, eq).trim().lowercase()
                    val v = line.substring(eq + 1).trim()
                    val target = when {
                        section == "[interface]" -> iface
                        section == "[peer]" && peers == 1 -> peer // first peer only
                        else -> null
                    } ?: continue
                    target[k] = if (target.containsKey(k)) target[k] + "," + v else v
                }

                val priv = key(iface["privatekey"]) ?: return null
                val pub = key(peer["publickey"]) ?: return null
                val psk = key(peer["presharedkey"]) ?: ByteArray(32)

                val addresses = (iface["address"] ?: return null).split(',').mapNotNull { a ->
                    val s = a.trim()
                    if (s.isEmpty()) return@mapNotNull null
                    val ip = s.substringBefore('/')
                    val addr = literal(ip) ?: return@mapNotNull null
                    val prefix = s.substringAfter('/', "").toIntOrNull()
                        ?: if (addr.address.size == 4) 32 else 128
                    Pair(addr, prefix)
                }
                if (addresses.isEmpty()) return null

                val dns = (iface["dns"] ?: "").split(',').mapNotNull { literal(it.trim()) }
                val mtu = iface["mtu"]?.toIntOrNull()?.coerceIn(1280, 1500) ?: 1420

                val endpoint = peer["endpoint"] ?: return null
                val host: String
                val port: Int
                if (endpoint.startsWith("[")) {
                    host = endpoint.substring(1, endpoint.indexOf(']'))
                    port = endpoint.substringAfterLast(':').toInt()
                } else {
                    host = endpoint.substringBeforeLast(':')
                    port = endpoint.substringAfterLast(':').toInt()
                }
                if (host.isEmpty() || port !in 1..65535) return null
                val keepalive = peer["persistentkeepalive"]?.toIntOrNull() ?: 0

                return WgConfig(priv, addresses, dns, mtu, pub, psk, host, port, keepalive)
            } catch (_: Exception) {
                return null
            }
        }

        private fun key(v: String?): ByteArray? {
            if (v.isNullOrBlank()) return null
            return try {
                Base64.getDecoder().decode(v.trim()).takeIf { it.size == 32 }
            } catch (_: Exception) {
                null
            }
        }

        /** Only accepts written-out IP addresses (never looks anything up). */
        private fun literal(s: String): InetAddress? {
            if (s.isEmpty()) return null
            val looksLikeIp = s.contains(':') || s.all { it.isDigit() || it == '.' }
            if (!looksLikeIp) return null
            return try {
                InetAddress.getByName(s)
            } catch (_: Exception) {
                null
            }
        }
    }
}
