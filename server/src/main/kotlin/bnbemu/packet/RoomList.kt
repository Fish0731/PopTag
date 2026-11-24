package bnbemu.packet

import bnbemu.ACP
import bnbemu.netty.PKT
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val LOCAL_ROOM_NUMBER = 75

// The native S87 reader opens a single SSD stream and reads one entry per count.
fun RoomList(rooms: List<RoomEntry> = EN_SAMPLE_ROOMS) = PKT {
    Encode2(rooms.size)
    if (rooms.isNotEmpty()) {
        val names = rooms.map { it.name.toByteArray(ACP) }
        val payloadSize = 1 + names.sumOf { it.size + 25 }
        val blob = ByteBuffer.allocate(5 + payloadSize).order(ByteOrder.LITTLE_ENDIAN)
        blob.put(0x18).putInt(payloadSize).put(0) // SSD envelope and uncompressed stream type
        rooms.forEachIndexed { index, room ->
            val name = names[index]
            blob.putShort(room.number.toShort()).putInt(room.handle).putInt(name.size).put(name)
            blob.put(room.options)
            blob.putInt(room.sessionIndex).put(0).put(0)
        }
        check(!blob.hasRemaining())
        blob.flip()
        Encode4(blob.remaining())
        while (blob.remaining() >= 4) Encode4(blob.int)
        if (blob.remaining() >= 2) Encode2(blob.short.toInt() and 0xffff)
        if (blob.hasRemaining()) Encode1(blob.get().toInt() and 0xff)
    }
}

class RoomEntry internal constructor(
    val name: String,
    internal val number: Int,
    internal val handle: Int,
    internal val sessionIndex: Int,
    internal val options: ByteArray,
) {
    companion object {
        internal fun local() = RoomEntry(
            name = "",
            number = LOCAL_ROOM_NUMBER,
            handle = 0,
            sessionIndex = 0,
            options = byteArrayOf(0, 0, 0, 0, 0, 0, 8, 0, 0)
        )
    }
}

// Preserve the original rd capture, including the as-yet-unlabelled settings.
private val EN_SAMPLE_ROOMS = listOf(
    RoomEntry("rd", 61, 119, 4, byteArrayOf(0, 0x90.toByte(), 0, 0, 0, 8, 7, 8, 16))
)
