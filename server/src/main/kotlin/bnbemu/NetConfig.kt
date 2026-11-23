package bnbemu

import bnbemu.netty.ClientSocket
import io.netty.channel.ChannelHandlerContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Network advertisement and runtime configuration for LAN / public WAN multiplayer.
 *
 * Resolution priority for advertised `ServerIP`:
 * 1. JVM property `-Dbnbemu.serverip=<ip|host>`
 * 2. Environment variable `POPTAG_SERVER_IP`
 * 3. `Config.yaml` key `ServerIP`
 * 4. Default `"127.0.0.1"` (auto-detects per-connection local IPv4 via [enterContext])
 */
object NetConfig {
    private val configuredIp: String
    private val configuredDebug: Boolean
    private val tlIp = ThreadLocal<String>()

    init {
        var ip = System.getProperty("bnbemu.serverip")?.takeUnless(::isUnsetOrAuto)
            ?: System.getenv("POPTAG_SERVER_IP")?.takeUnless(::isUnsetOrAuto)
        var dbg = false

        try {
            val cfg = Paths.get("Config.yaml")
            if (Files.exists(cfg)) {
                for (raw in Files.readAllLines(cfg, StandardCharsets.UTF_8)) {
                    var line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val hash = line.indexOf('#')
                    if (hash >= 0) line = line.substring(0, hash).trim()
                    when {
                        line.startsWith("ServerIP:") -> {
                            val v = stripQuotes(line.substring("ServerIP:".length).trim())
                            if (v.isNotEmpty() && ip == null) ip = v
                        }
                        line.startsWith("Debug:") -> {
                            val v = stripQuotes(line.substring("Debug:".length).trim())
                            dbg = v.equals("true", ignoreCase = true)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        ip = ip?.let { stripQuotes(it.trim()) }
        val resolvedIp = when {
            ip.isNullOrEmpty() || ip.equals("auto", ignoreCase = true) -> "127.0.0.1"
            isLiteralIpv4(ip) -> ip
            else -> {
                val dns = resolveHostToIpv4(ip)
                if (dns != null) {
                    println("[NetConfig] Resolved ServerIP '$ip' -> $dns")
                    dns
                } else {
                    println("[NetConfig] Failed to resolve ServerIP '$ip', falling back to 127.0.0.1")
                    "127.0.0.1"
                }
            }
        }

        configuredIp = resolvedIp
        configuredDebug = dbg
        println(
            "[NetConfig] Advertised ServerIP=$configuredIp" +
                if (configuredIp == "127.0.0.1") {
                    " (auto-detect per connection; set ServerIP in Config.yaml for public WAN)"
                } else {
                    " (fixed public/LAN IP)"
                }
        )
    }

    val debug: Boolean
        get() = configuredDebug

    val serverIp: String
        get() = tlIp.get() ?: configuredIp

    val serverIpInt: Int
        get() = ipToInt(serverIp)

    fun enterContext(ctx: ChannelHandlerContext?) {
        if (ctx == null) return
        if (configuredIp != "127.0.0.1") {
            tlIp.set(configuredIp)
            return
        }
        val localIp = extractLocalIpv4(ctx.channel().localAddress())
        if (localIp != null) tlIp.set(localIp) else tlIp.remove()
    }

    fun exitContext() {
        tlIp.remove()
    }

    fun resolveServerIpFor(c: ClientSocket?): String {
        if (configuredIp != "127.0.0.1") return configuredIp
        val localIp = extractLocalIpv4(c?.ch?.localAddress())
        return localIp ?: configuredIp
    }

    fun resolveServerIpIntFor(c: ClientSocket?): Int = ipToInt(resolveServerIpFor(c))

    fun extractLocalIpv4(sa: SocketAddress?): String? {
        if (sa !is InetSocketAddress) return null
        val addr = sa.address ?: return null
        if (addr is Inet4Address) return addr.hostAddress
        val host = addr.hostAddress
        if (host != null && host.startsWith("::ffff:")) return host.substring(7)
        if (addr.isLoopbackAddress) return "127.0.0.1"
        return null
    }

    fun ipToInt(ip: String?): Int {
        if (ip == null) return 0x7F000001
        var trimmed = ip.trim()
        if (!isLiteralIpv4(trimmed)) {
            resolveHostToIpv4(trimmed)?.let { trimmed = it }
        }
        val parts = trimmed.split('.')
        if (parts.size != 4) return 0x7F000001
        return try {
            var ipInt = 0
            for (p in parts) {
                ipInt = (ipInt shl 8) or (p.trim().toInt() and 0xFF)
            }
            ipInt
        } catch (_: Exception) {
            0x7F000001
        }
    }

    private fun isUnsetOrAuto(s: String?): Boolean {
        if (s == null) return true
        val t = stripQuotes(s.trim())
        return t.isEmpty() || t.equals("auto", ignoreCase = true)
    }

    private fun stripQuotes(s: String): String {
        if (s.length >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length - 1).trim()
        }
        return s
    }

    private fun isLiteralIpv4(s: String?): Boolean {
        if (s == null) return false
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun resolveHostToIpv4(host: String): String? = try {
        InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress
    } catch (_: Exception) {
        null
    }
}
