package bnbemu.server

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks each player's observed UDP socket address and advertised LAN endpoints
 * from UDP handshake probes (op 0 on port 3839/4849).
 */
object UdpPeers {
    data class Endpoint(val ip: Int, val port: Int)

    private val book = ConcurrentHashMap<Int, List<Endpoint>>()
    private val observed = ConcurrentHashMap<Int, InetSocketAddress>()
    private val byAddr = ConcurrentHashMap<InetSocketAddress, Int>()

    fun userIdOf(addr: InetSocketAddress): Int? = byAddr[addr]

    fun observedOf(userId: Int): InetSocketAddress? = observed[userId]

    fun endpointsOf(userId: Int): List<Endpoint> = book[userId].orEmpty()

    fun register(userId: Int, seen: InetSocketAddress, lan: List<Endpoint>) {
        val raw = seen.address?.address
        val seenEp = if (raw != null && raw.size == 4) {
            val ipInt = ((raw[0].toInt() and 0xFF) shl 24) or
                ((raw[1].toInt() and 0xFF) shl 16) or
                ((raw[2].toInt() and 0xFF) shl 8) or
                (raw[3].toInt() and 0xFF)
            Endpoint(ipInt, seen.port)
        } else {
            null
        }

        val list = ArrayList<Endpoint>(3)
        if (seenEp != null) list.add(seenEp)
        for (ep in lan) {
            if (ep.ip != 0 && ep.port > 0 && list.none { it.ip == ep.ip && it.port == ep.port }) {
                list.add(ep)
            }
        }
        book[userId] = list
        observed.put(userId, seen)?.let { prev ->
            if (prev != seen) byAddr.remove(prev, userId)
        }
        byAddr[seen] = userId
        println("[UDP] P2P peer user=$userId -> ${seen.address?.hostAddress}:${seen.port}")
    }

    fun clearUser(userId: Int) {
        book.remove(userId)
        observed.remove(userId)?.let { prev -> byAddr.remove(prev, userId) }
        byAddr.entries.removeIf { it.value == userId }
    }
}
