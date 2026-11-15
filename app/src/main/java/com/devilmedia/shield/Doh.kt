package com.devilmedia.shield

import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL

/**
 * Private (encrypted) lookups: sends allowed lookups over HTTPS ("DNS over HTTPS"),
 * so the internet provider or anyone on public Wi-Fi can't see which sites are visited.
 * If it can't connect (for example on a hotel Wi-Fi sign-in page), Devil Shield
 * quietly uses the network's normal lookups for a minute and then tries again.
 */
object Doh {
    private val SERVERS = listOf(
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/dns-query"
    )

    @Volatile private var pausedUntil = 0L

    fun query(dns: ByteArray): ByteArray? {
        if (SystemClock.elapsedRealtime() < pausedUntil) return null
        for (server in SERVERS) {
            try {
                val c = URL(server).openConnection() as HttpURLConnection
                c.connectTimeout = 3000
                c.readTimeout = 3000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/dns-message")
                c.setRequestProperty("Accept", "application/dns-message")
                c.outputStream.use { it.write(dns) }
                if (c.responseCode == 200) {
                    val answer = c.inputStream.use { it.readBytes() }
                    if (answer.size >= 12) return answer
                } else {
                    try {
                        c.errorStream?.close()
                    } catch (_: Exception) {
                    }
                }
                // Not disconnecting lets Android reuse the connection, which keeps lookups fast.
            } catch (_: Exception) {
                // Try the next server.
            }
        }
        pausedUntil = SystemClock.elapsedRealtime() + 60_000
        return null
    }
}
