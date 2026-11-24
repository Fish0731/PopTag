package bnbemu.netty

import bnbemu.packet.RoomList
import bnbemu.server.*
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RoomSeatReplyTest {
    @Test fun autoReadyAcknowledgesTheRoomStageWithoutRecreatingIt() {
        val client = ClientSocket()
        val channel = EmbeddedChannel(client)
        val member = Room.slots[0].user
        val mapId = Room.mapId
        val mode = Room.localGameMode
        try {
            val owner = User(client, 29315, "auto-ready-owner")
            Room.slots[0].user = owner
            Room.mapId = 179
            Room.localGameMode = 16
            for (intent in 0..1) {
                val request = Unpooled.buffer()
                SendPacketBase(request, 3).apply { Encode2(84); Encode1(intent); Encode4(0) }
                channel.writeInbound(request)
                val reply = channel.readOutbound<PKT>()
                assertEquals(304, OpcodeManager.getSendOp(reply::class.java))
                val body = Unpooled.buffer()
                try {
                    reply.encode(SendPacketBase(body, 3))
                    assertEquals(0, body.Decode1)
                    assertFalse(body.isReadable)
                } finally { body.release() }
                assertNull(channel.readOutbound<PKT>())
                assertSame(owner, Room.slots[0].user)
                assertEquals(179, Room.mapId)
                assertEquals(16, Room.localGameMode)
            }
        } finally {
            Room.slots[0].user = member
            Room.mapId = mapId
            Room.localGameMode = mode
            channel.finishAndReleaseAll()
        }
    }

    @Test fun creatingARoomRegistersTheSameRoomNumberBeforeEntering() {
        val client = ClientSocket()
        val channel = EmbeddedChannel(client)
        val saved = Room.slots.map { Triple(it.user, it.isAI, it.meta.copyOf()) }
        val mode = Room.localGameMode
        try {
            client.users.add(User(client, 29314, "local-room-registration"))
            val request = Unpooled.buffer()
            SendPacketBase(request, 3).apply {
                Encode2(75); Encode2(0); Encode2(0)
                Encode1(0); Encode1(1); Encode1(6); Encode1(15); Encode4(66)
            }
            channel.writeInbound(request)
            val list = Unpooled.buffer()
            val enter = Unpooled.buffer()
            val capture = Unpooled.buffer()
            try {
                val registration = channel.readOutbound<PKT>()
                val session = channel.readOutbound<PKT>()
                assertEquals(87, OpcodeManager.getSendOp(registration::class.java))
                assertEquals(83, OpcodeManager.getSendOp(session::class.java))
                registration.encode(SendPacketBase(list, 3))
                session.encode(SendPacketBase(enter, 3))
                assertEquals(1, list.Decode2)
                val length = list.Decode4
                assertEquals(31, length)
                val plain = java.nio.ByteBuffer.allocate(length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                while (list.readableBytes() >= 4) plain.putInt(list.Decode4)
                if (list.readableBytes() >= 2) plain.putShort(list.Decode2.toShort())
                if (list.isReadable) plain.put(list.Decode1.toByte())
                plain.flip()
                assertEquals(0x18, plain.get().toInt())
                assertEquals(26, plain.int)
                assertEquals(0, plain.get().toInt(), "Native SSD reader requires a raw-stream flag")
                enter.Decode1; enter.Decode1
                assertEquals(enter.Decode2, plain.short.toInt())
                assertEquals(0, plain.int, "No external session handle")
                assertEquals(0, plain.int, "Local room label length")

                RoomList().encode(SendPacketBase(capture, 3))
                val expected = "A5 68 96 CA 53 B4 96 CA 4F 8D 96 F7 53 95 96 CA 53 E2 96 CA 53 97 06 CA 37 E7 9E CA 53 95 92 DA 5B 92 96 CA 53 95 5A"
                    .split(' ').map { it.toInt(16).toByte() }.toByteArray()
                val actual = ByteArray(capture.readableBytes()).also { capture.readBytes(it) }
                assertArrayEquals(expected, actual, "Keep the official rd room sample byte-for-byte")
            } finally { list.release(); enter.release(); capture.release() }
        } finally {
            Room.reset(); Room.localGameMode = mode
            Room.slots.forEachIndexed { i, slot ->
                slot.user = saved[i].first; slot.isAI = saved[i].second; saved[i].third.copyInto(slot.meta)
            }
            channel.finishAndReleaseAll()
        }
    }

    @Test fun allSeatRequestsAcknowledgeWithoutLosingEitherHuman() {
        OpcodeManager.recvOps
        val c=ClientSocket();val ch=EmbeddedChannel(c)
        val saved=Room.slots.map {Triple(it.user,it.isAI,it.meta.copyOf())};val mode=Room.localGameMode
        try {
            c.users.addAll(listOf(User(c,29312,"seat-human-one"),User(c,29313,"seat-human-two")))
            for(m in listOf(0,14,15,16)) for(seat in 0..7) for(action in 0..1) {
                Room.reset();Room.localGameMode=m
                Room.slots.forEach {it.user=null;it.isAI=false;it.state=1}
                c.users.forEachIndexed {i,u->Room.slots[i].user=u;Room.slots[i].state=0}
                val b=Unpooled.buffer();SendPacketBase(b,3).apply {Encode2(89);Encode1((seat shl 4) or action)}
                ch.writeInbound(b)
                var response:Int?=null
                while(true) {
                    val p=ch.readOutbound<PKT>() ?: break
                    if(OpcodeManager.getSendOp(p::class.java)==106) {
                        val wire=Unpooled.buffer()
                        try {p.encode(SendPacketBase(wire,3));response=wire.Decode1} finally {wire.release()}
                    }
                }
                assertNotNull(response,"S106 unlock required: mode=$m seat=$seat action=$action")
                assertEquals(seat,response!! ushr 4)
                assertEquals(if(Room.slots[seat].state.toInt() and FLAG_CLOSED != 0) 1 else 0,response!! and 1)
                assertSame(c.users[0],Room.slots[0].user);assertSame(c.users[1],Room.slots[1].user)
                assertFalse(Room.slots[1].isAI)
                if(m in 14..15 || (m==16 && seat<4))assertFalse(Room.slots[seat].isAI)
            }
        } finally {
            Room.reset();Room.localGameMode=mode
            Room.slots.forEachIndexed {i,s->s.user=saved[i].first;s.isAI=saved[i].second;saved[i].third.copyInto(s.meta)}
            ch.finishAndReleaseAll()
        }
    }
}
