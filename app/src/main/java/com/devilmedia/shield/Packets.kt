package com.devilmedia.shield

/**
 * Reads website lookups (DNS questions) out of network packets and builds the replies.
 * Only lookups pass through Devil Shield; all other traffic goes straight to the internet.
 */
object Packets {

    class Query(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val dns: ByteArray
    )

    /** Returns the lookup inside an IPv4/UDP packet to port 53, or null. */
    fun parseUdpDns(pkt: ByteArray, len: Int): Query? {
        if (len < 28) return null
        val version = (pkt[0].toInt() shr 4) and 0xF
        if (version != 4) return null
        val ihl = (pkt[0].toInt() and 0xF) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if ((pkt[9].toInt() and 0xFF) != 17) return null // UDP only
        val total = minOf(u16(pkt, 2), len)
        val srcPort = u16(pkt, ihl)
        val dstPort = u16(pkt, ihl + 2)
        if (dstPort != 53) return null
        val start = ihl + 8
        if (total < start + 12) return null
        return Query(
            pkt.copyOfRange(12, 16),
            pkt.copyOfRange(16, 20),
            srcPort,
            dstPort,
            pkt.copyOfRange(start, total)
        )
    }

    /** The site name being looked up, and where the question ends. */
    fun question(dns: ByteArray): Pair<String, Int>? {
        if (dns.size < 12 || u16(dns, 4) < 1) return null
        val sb = StringBuilder()
        var i = 12
        while (true) {
            if (i >= dns.size) return null
            val l = dns[i].toInt() and 0xFF
            if (l == 0) {
                i++
                break
            }
            if ((l and 0xC0) != 0) return null
            if (i + 1 + l > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..l) sb.append((dns[i + k].toInt() and 0xFF).toChar())
            i += 1 + l
            if (sb.length > 253) return null
        }
        if (i + 4 > dns.size) return null
        return Pair(sb.toString().lowercase(), i + 4)
    }

    /** A "this site doesn't exist" answer, so the blocked site can't be reached. */
    fun blockedAnswer(dns: ByteArray, questionEnd: Int): ByteArray {
        val r = dns.copyOf(questionEnd)
        r[2] = ((dns[2].toInt() and 0x79) or 0x80).toByte() // reply; keep opcode and "recursion desired"
        r[3] = (0x80 or 3).toByte() // recursion available; "no such name"
        r[4] = 0; r[5] = 1 // one question
        r[6] = 0; r[7] = 0; r[8] = 0; r[9] = 0; r[10] = 0; r[11] = 0
        return r
    }

    /** Wraps an answer in an IPv4/UDP packet going back to the app that asked. */
    fun reply(q: Query, payload: ByteArray): ByteArray =
        udp4(q.dstIp, q.srcIp, q.dstPort, q.srcPort, payload)

    /** Builds an IPv4/UDP packet. */
    fun udp4(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        p[6] = 0x40 // don't fragment
        p[8] = 64 // time to live
        p[9] = 17 // UDP
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        put16(p, 10, checksum(p, 20))
        put16(p, 20, srcPort)
        put16(p, 22, dstPort)
        put16(p, 24, 8 + payload.size)
        // UDP checksum 0 = "not used", which is allowed for IPv4.
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    /**
     * If [pkt] is a lookup answer (IPv4, UDP from port 53) from [from], returns its
     * destination port, else -1. Used to recognise answers coming back through the VPN.
     */
    fun dnsAnswerPort(pkt: ByteArray, from: ByteArray): Int {
        if (pkt.size < 28 || ((pkt[0].toInt() shr 4) and 0xF) != 4) return -1
        if ((pkt[9].toInt() and 0xFF) != 17) return -1
        for (i in 0 until 4) if (pkt[12 + i] != from[i]) return -1
        val ihl = (pkt[0].toInt() and 0xF) * 4
        if (pkt.size < ihl + 8 || u16(pkt, ihl) != 53) return -1
        return u16(pkt, ihl + 2)
    }

    /** Changes the sender address of an IPv4/UDP packet (in place) and fixes its checksums. */
    fun rewriteSource(pkt: ByteArray, newSrc: ByteArray) {
        val ihl = (pkt[0].toInt() and 0xF) * 4
        System.arraycopy(newSrc, 0, pkt, 12, 4)
        put16(pkt, 10, 0)
        put16(pkt, 10, checksum(pkt, ihl))
        put16(pkt, ihl + 6, 0) // UDP checksum not used
    }

    private fun checksum(b: ByteArray, len: Int): Int {
        var sum = 0L
        var i = 0
        while (i < len) {
            sum += (((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFFL) + (sum shr 16)
        return (sum.inv() and 0xFFFFL).toInt()
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte()
        b[o + 1] = v.toByte()
    }
}
