package com.devilmedia.shield

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ShieldTests {

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun blake2sMatchesKnownAnswer() {
        assertArrayEquals(hex("69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9"), Wg.hash(ByteArray(0)))
    }

    @Test
    fun x25519MatchesRfc7748() {
        val priv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        assertArrayEquals(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), Wg.publicKey(priv))
    }

    @Test
    fun aeadRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val msg = "hello".toByteArray()
        val sealed = Wg.seal(key, 7, msg, 0, msg.size, null)
        assertArrayEquals(msg, Wg.open(key, 7, sealed, 0, sealed.size, null))
        assertEquals(null, Wg.open(key, 8, sealed, 0, sealed.size, null))
    }

    @Test
    fun parsesSurfsharkStyleConfig() {
        val cfg = WgConfig.parse(
            """
            [Interface]
            Address = 10.14.0.2/16
            PrivateKey = ${java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })}
            DNS = 162.252.172.57, 149.154.159.92

            [Peer]
            PublicKey = ${java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 })}
            AllowedIPs = 0.0.0.0/0
            Endpoint = uk-lon.prod.surfshark.com:51820
            """.trimIndent()
        )
        assertNotNull(cfg)
        cfg!!
        assertEquals("uk-lon.prod.surfshark.com", cfg.endpointHost)
        assertEquals(51820, cfg.endpointPort)
        assertEquals(16, cfg.addresses[0].second)
        assertEquals(2, cfg.dns.size)
        assertEquals(null, WgConfig.parse("not a vpn file"))
    }

    @Test
    fun blocklistMatchesSitesAndSubsites() {
        val list = LongList()
        for (d in listOf("bad-site.com", "phish.example.org")) list.add(DomainSet.hash(d))
        val set = DomainSet(list.sortedUnique())
        assertTrue(Domains.matches("bad-site.com", set))
        assertTrue(Domains.matches("cdn.bad-site.com", set))
        assertTrue(Domains.matches("phish.example.org", set))
        assertFalse(Domains.matches("example.org", set))
        assertFalse(Domains.matches("good-site.com", set))
        assertEquals("evil.net", Domains.parseLine("0.0.0.0 evil.net # comment"))
    }

    /** Connects to a real WireGuard server (started by the GitHub build) and pings through it. */
    @Test
    fun connectsAndPingsThroughRealWireGuard() {
        val clientKey = System.getenv("WG_TEST_CLIENT_KEY")
        val serverPub = System.getenv("WG_TEST_SERVER_PUB")
        assumeTrue("No test WireGuard server here", !clientKey.isNullOrBlank() && !serverPub.isNullOrBlank())

        val cfg = WgConfig.parse(
            """
            [Interface]
            PrivateKey = $clientKey
            Address = 10.99.0.2/32
            DNS = 10.99.0.1

            [Peer]
            PublicKey = $serverPub
            AllowedIPs = 0.0.0.0/0
            Endpoint = 127.0.0.1:51820
            PersistentKeepalive = 25
            """.trimIndent()
        )!!

        val received = LinkedBlockingQueue<ByteArray>()
        val tunnel = WgTunnel(cfg, protect = {}, deliver = { received.add(it) })
        tunnel.start()
        try {
            val deadline = System.currentTimeMillis() + 15_000
            while (!tunnel.isConnected && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("Handshake with WireGuard didn't complete: ${WgTunnel.detail}", tunnel.isConnected)

            val ping = icmpEcho(byteArrayOf(10, 99, 0, 2), byteArrayOf(10, 99, 0, 1))
            var reply: ByteArray? = null
            for (attempt in 1..5) {
                tunnel.send(ping, ping.size)
                val p = received.poll(2, TimeUnit.SECONDS) ?: continue
                if (p.size >= 28 && p[9].toInt() == 1 && p[20].toInt() == 0) {
                    reply = p
                    break
                }
            }
            assertNotNull("No ping reply came back through the VPN", reply)
        } finally {
            tunnel.stop()
        }
    }

    private fun icmpEcho(src: ByteArray, dst: ByteArray): ByteArray {
        val payload = ByteArray(16) { it.toByte() }
        val p = ByteArray(28 + payload.size)
        p[0] = 0x45
        p[2] = (p.size shr 8).toByte(); p[3] = p.size.toByte()
        p[8] = 64
        p[9] = 1 // ICMP
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        val ipSum = sum(p, 0, 20)
        p[10] = (ipSum shr 8).toByte(); p[11] = ipSum.toByte()
        p[20] = 8 // echo request
        p[24] = 0x12; p[25] = 0x34 // id
        p[27] = 1 // sequence
        System.arraycopy(payload, 0, p, 28, payload.size)
        val icmpSum = sum(p, 20, p.size - 20)
        p[22] = (icmpSum shr 8).toByte(); p[23] = icmpSum.toByte()
        return p
    }

    private fun sum(b: ByteArray, off: Int, len: Int): Int {
        var s = 0L
        var i = 0
        while (i < len) {
            val hi = b[off + i].toInt() and 0xFF
            val lo = if (i + 1 < len) b[off + i + 1].toInt() and 0xFF else 0
            s += ((hi shl 8) or lo).toLong()
            i += 2
        }
        while ((s shr 16) != 0L) s = (s and 0xFFFFL) + (s shr 16)
        return (s.inv() and 0xFFFFL).toInt()
    }
}
