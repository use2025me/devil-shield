package com.devilmedia.shield

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Camera safety checks, written to be reused in the Devil Media Group CCTV app.
 * It only reads the public sign-in page a camera already shows on the home network,
 * and asks the person's own router what it has opened to the internet. It never
 * tries to log in to anything.
 */
object CameraCheck {

    /** Works out a camera's brand from its sign-in page. Returns null if unknown. */
    fun brand(ip: String): String? {
        for (scheme in listOf("http", "https")) {
            try {
                val c = URL("$scheme://$ip/").openConnection() as HttpURLConnection
                c.connectTimeout = 1500
                c.readTimeout = 2000
                c.instanceFollowRedirects = true
                val server = c.getHeaderField("Server") ?: ""
                val bodyText = try {
                    (if (c.responseCode < 400) c.inputStream else c.errorStream)?.use { s ->
                        val buf = ByteArray(8192)
                        val n = s.read(buf)
                        if (n > 0) String(buf, 0, n) else ""
                    } ?: ""
                } catch (_: Exception) {
                    ""
                }
                c.disconnect()
                val t = (server + " " + bodyText).lowercase()
                val found = when {
                    "hikvision" in t || "app-webs" in t || "doc/page/login.asp" in t -> "Hikvision"
                    "dahua" in t || "rpc2_login" in t -> "Dahua"
                    "reolink" in t -> "Reolink"
                    "uniview" in t -> "Uniview"
                    "axis" in t && "camera" in t -> "Axis"
                    "tapo" in t || "tp-link" in t -> "TP-Link"
                    "annke" in t -> "Annke"
                    "swann" in t -> "Swann"
                    "foscam" in t -> "Foscam"
                    else -> null
                }
                if (found != null) return found
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Plain-language advice for a camera brand. */
    fun advice(brand: String?): String = when (brand) {
        "Hikvision" ->
            "Hikvision cameras and recorders running older software have had serious, widely used security holes. " +
                "Update to the latest software from Hikvision's website (or through the Hik-Connect app), change any " +
                "default password, and switch off UPnP in the recorder's network settings if you don't need it."
        "Dahua" ->
            "Older Dahua software has had serious security holes, including ways to skip the password. Update to the " +
                "latest software from Dahua's website, change any default password, and switch off UPnP and P2P if " +
                "you don't use them."
        null ->
            "Make sure you've changed its default password, its software is up to date, and you view it away from " +
                "home through the maker's app rather than by \"port forwarding\" on the router."
        else ->
            "Update it to the latest software from $brand, use a strong password that isn't the default, and view " +
                "it away from home through $brand's app rather than by \"port forwarding\" on the router."
    }
}

/** Asks the home router (by UPnP) which devices it has opened up to the internet. */
object Upnp {

    class Mapping(
        val externalPort: Int,
        val internalIp: String,
        val internalPort: Int,
        val protocol: String,
        val description: String
    )

    /** Port mappings the router has made, or null if the router doesn't answer UPnP (often means it's off: good). */
    fun portMappings(): List<Mapping>? {
        val location = discover() ?: return null
        val (control, serviceType) = controlUrl(location) ?: return null
        val out = ArrayList<Mapping>()
        for (i in 0 until 64) {
            val m = mappingAt(control, serviceType, i) ?: break
            out += m
        }
        return out
    }

    private fun discover(): String? {
        val targets = listOf(
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2"
        )
        return try {
            DatagramSocket().use { s ->
                s.soTimeout = 1500
                val group = InetAddress.getByName("239.255.255.250")
                for (st in targets) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                    val bytes = msg.toByteArray()
                    s.send(DatagramPacket(bytes, bytes.size, group, 1900))
                }
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + 3500
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val reply = String(buf, 0, p.length)
                        val loc = reply.lines()
                            .firstOrNull { it.lowercase().startsWith("location:") }
                            ?.substringAfter(':')?.trim()
                        if (!loc.isNullOrEmpty()) return loc
                    } catch (_: SocketTimeoutException) {
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun controlUrl(location: String): Pair<String, String>? {
        return try {
            val c = URL(location).openConnection() as HttpURLConnection
            c.connectTimeout = 2500
            c.readTimeout = 3000
            val xml = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            c.disconnect()
            val services = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            for (svc in services) {
                val block = svc.groupValues[1]
                val type = Regex("<serviceType>\\s*(urn:schemas-upnp-org:service:WAN(?:IP|PPP)Connection:\\d)\\s*</serviceType>")
                    .find(block)?.groupValues?.get(1) ?: continue
                val ctrl = Regex("<controlURL>\\s*(.*?)\\s*</controlURL>").find(block)?.groupValues?.get(1) ?: continue
                return Pair(URL(URL(location), ctrl).toString(), type)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun mappingAt(control: String, serviceType: String, index: Int): Mapping? {
        return try {
            val body = "<?xml version=\"1.0\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                "<u:GetGenericPortMappingEntry xmlns:u=\"$serviceType\">" +
                "<NewPortMappingIndex>$index</NewPortMappingIndex>" +
                "</u:GetGenericPortMappingEntry></s:Body></s:Envelope>"
            val c = URL(control).openConnection() as HttpURLConnection
            c.connectTimeout = 2500
            c.readTimeout = 3000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPAction", "\"$serviceType#GetGenericPortMappingEntry\"")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) {
                c.disconnect()
                return null
            }
            val xml = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            c.disconnect()
            fun tag(name: String) = Regex("<$name>\\s*(.*?)\\s*</$name>").find(xml)?.groupValues?.get(1) ?: ""
            val enabled = tag("NewEnabled")
            if (enabled == "0") return Mapping(0, "", 0, "", "(switched off)") // still counts as an entry
            Mapping(
                externalPort = tag("NewExternalPort").toIntOrNull() ?: 0,
                internalIp = tag("NewInternalClient"),
                internalPort = tag("NewInternalPort").toIntOrNull() ?: 0,
                protocol = tag("NewProtocol"),
                description = tag("NewPortMappingDescription")
            )
        } catch (_: Exception) {
            null
        }
    }
}
