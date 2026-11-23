package bnbemu.packet

import bnbemu.ACP
import bnbemu.netty.OpcodeManager
import bnbemu.netty.PKT
import bnbemu.netty.SendPacketBase
import bnbemu.server.UdpPeers
import bnbemu.server.User
import bnbemu.server.localBombStyle
import java.io.ByteArrayOutputStream

data class LobbyRoom(
    val roomNo: Int,
    val key: Int,
    val name: String,
    val f48: Int = 0,
    val f54: Int = 0,
    val f6c: Int = 0,
    val f78: Int = 0,
    val f84: Int = 0,
    val f90: Int = 0,
    val f9c: Int = 0,
    val f60: Int = 0,
    val fc0: Int = 0,
    val fa8: Int = 0,
    val fb4: Int = 0,
) {
    fun write(o: ByteArrayOutputStream) {
        val nb = name.toByteArray(ACP)
        o.u16le(roomNo)
        o.u32le(key)
        o.u32le(nb.size)
        o.write(nb)
        o.u8(f48)
        o.u8(f54)
        o.u8(f6c)
        o.u16le(f78)
        o.u8(f84)
        o.u8(f90)
        o.u8(f9c)
        o.u8(f60)
        o.u32le(fc0)
        o.u8(fa8)
        o.u8(fb4)
    }
}

object MpPackets {
    init {
        OpcodeManager.sendOpCache[RoomListDeltaPacket::class.java] = 86
        OpcodeManager.sendOpCache[RoomListOfPacket::class.java] = 87
        OpcodeManager.sendOpCache[SlotPlayerInfoPacket::class.java] = 100
        OpcodeManager.sendOpCache[BomberActionBroadcastPacket::class.java] = 146
        OpcodeManager.sendOpCache[GameBoardPeersPacket::class.java] = 136
    }

    fun SlotPlayerInfo(slot: Int, user: User?, meta: ByteArray?, inRoom: Boolean): PKT =
        SlotPlayerInfoPacket(slot, user, meta, inRoom)

    fun BomberActionBroadcast(rawPayload: ByteArray): PKT =
        BomberActionBroadcastPacket(rawPayload)

    fun GameBoardPeers(
        peers: Map<Int, List<UdpPeers.Endpoint>>,
        peerTimeoutSec: Int,
        entryCount: Int,
        timeLimitSec: Int = 180
    ): PKT = GameBoardPeersPacket(peers, peerTimeoutSec, entryCount, timeLimitSec)

    fun RoomListOf(rooms: List<LobbyRoom>): PKT = RoomListOfPacket(rooms)

    fun RoomListDelta(removed: List<Pair<Int, Int>>): PKT = RoomListDeltaPacket(removed)

    fun ssdEncodeFull(plain: ByteArray): ByteArray {
        val out = ByteArray(plain.size)
        var i = 0
        while (i + 4 <= plain.size) {
            val v = (plain[i].toInt() and 0xFF) or
                ((plain[i + 1].toInt() and 0xFF) shl 8) or
                ((plain[i + 2].toInt() and 0xFF) shl 16) or
                ((plain[i + 3].toInt() and 0xFF) shl 24)
            val enc = v xor 0x96CA5395.toInt()
            out[i] = (enc ushr 24).toByte()
            out[i + 1] = (enc ushr 16).toByte()
            out[i + 2] = (enc ushr 8).toByte()
            out[i + 3] = enc.toByte()
            i += 4
        }
        if (plain.size - i >= 2) {
            val v = (plain[i].toInt() and 0xFF) or ((plain[i + 1].toInt() and 0xFF) shl 8)
            val enc = v xor 0xA569
            out[i] = (enc ushr 8).toByte()
            out[i + 1] = enc.toByte()
            i += 2
        }
        if (i < plain.size) {
            out[i] = ((plain[i].toInt() and 0xFF) xor 0x5A).toByte()
        }
        return out
    }

    private fun wrapSsdBlock(payload: ByteArray): ByteArray {
        val totalLen = 6 + payload.size
        val block = ByteArray(totalLen)
        block[0] = 24
        block[1] = ((totalLen - 5) and 0xFF).toByte()
        System.arraycopy(payload, 0, block, 6, payload.size)
        return ssdEncodeFull(block)
    }

    private class RoomListOfPacket(private val rooms: List<LobbyRoom>) : PKT {
        override fun encode(out: SendPacketBase) {
            out.Encode2(rooms.size)
            if (rooms.isNotEmpty()) {
                val baos = ByteArrayOutputStream()
                rooms.forEach { it.write(baos) }
                val enc = wrapSsdBlock(baos.toByteArray())
                out.Encode4(enc.size)
                out.EncodeBuffer(enc)
            }
        }
    }

    private class RoomListDeltaPacket(private val removed: List<Pair<Int, Int>>) : PKT {
        override fun encode(out: SendPacketBase) {
            out.Encode2(0)
            out.Encode2(removed.size)
            if (removed.isNotEmpty()) {
                val baos = ByteArrayOutputStream()
                for ((roomNo, key) in removed) {
                    baos.u8(0)
                    baos.u16le(roomNo)
                    baos.u32le(key)
                }
                val enc = wrapSsdBlock(baos.toByteArray())
                out.Encode4(enc.size)
                out.EncodeBuffer(enc)
            }
        }
    }

    class SlotPlayerInfoPacket(
        private val slot: Int,
        private val user: User?,
        private val meta: ByteArray?,
        private val inRoom: Boolean
    ) : PKT {
        override fun encode(out: SendPacketBase) {
            val template = if (inRoom) EN_OP100_ROOM else EN_OP100_START
            val blob = template.clone()
            blob[0] = ((slot xor 0x5A) and 0xFF).toByte()
            if (meta != null && meta.size >= 3) {
                val state = meta[0].toInt() and 0xFF
                val bomber = meta[1].toInt() and 0xFF
                val color = meta[2].toInt() and 0xFF
                val base = if (inRoom) 10 else 9
                blob[base] = ((bomber xor 0xA5) and 0xFF).toByte()
                blob[base + 1] = ((state xor 0x69) and 0xFF).toByte()
                blob[base + 2] = ((color xor 0x5A) and 0xFF).toByte()
            }
            val bombStyle = localBombStyle(user)
            out.encodePracticePlayerInfo(blob, bombStyle, user, slot)
        }
    }

    class BomberActionBroadcastPacket(private val rawPayload: ByteArray) : PKT {
        override fun encode(out: SendPacketBase) {
            if (rawPayload.isNotEmpty()) {
                out.EncodeBuffer(rawPayload)
            }
        }
    }

    class GameBoardPeersPacket(
        private val peers: Map<Int, List<UdpPeers.Endpoint>>,
        private val peerTimeoutSec: Int,
        private val entryCount: Int,
        private val timeLimitSec: Int
    ) : PKT {
        override fun encode(out: SendPacketBase) {
            out.Encode4(1475740900)
            out.Encode4(0)
            out.Encode4(timeLimitSec)
            out.Encode4(0)
            out.Encode1(0)
            out.Encode4(0)
            out.Encode2(0)
            out.Encode1(peerTimeoutSec)
            for (i in 0 until entryCount) {
                val eps = peers[i]
                if (eps == null) {
                    out.Encode1(0)
                } else {
                    out.Encode1(1)
                    for (k in 0 until 3) {
                        val ep = eps.getOrNull(k)
                        if (ep != null) {
                            out.Encode4(ep.ip)
                            out.Encode2(ep.port)
                        } else {
                            out.Encode4(0)
                            out.Encode2(0)
                        }
                    }
                }
            }
        }
    }
}
