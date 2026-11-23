package bnbemu.netty

import bnbemu.NetConfig
import bnbemu.allocateNextUserId
import bnbemu.handler.CreatePracticeSessionReq
import bnbemu.handler.room.SelectBomberReq
import bnbemu.handler.room.SelectColorReq
import bnbemu.handler.room.SelectMapReq
import bnbemu.packet.MpPackets
import bnbemu.packet.PracticeRoomInfo
import bnbemu.packet.PracticeStartInfo
import bnbemu.server.FLAG_CHIEF
import bnbemu.server.FLAG_READY
import bnbemu.server.Lobby
import bnbemu.server.LocalShop
import bnbemu.server.Room
import bnbemu.server.UdpPeers
import bnbemu.server.User
import bnbemu.server.bomber
import bnbemu.server.color
import bnbemu.server.state
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.timeout.IdleStateEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

class MultiplayerAndMotionBlurTest {
    private data class Sent(val op: Int, val body: ByteArray)

    private fun makeClient(id: Int, name: String, remoteIp: String, localIp: String): Pair<ClientSocket, MutableList<Sent>> {
        val r = InetAddress.getByName(remoteIp)
        val l = InetAddress.getByName(localIp)
        val ch = object : EmbeddedChannel() {
            override fun remoteAddress(): SocketAddress = InetSocketAddress(r, 51000 + (id % 1000))
            override fun localAddress(): SocketAddress = InetSocketAddress(l, 3838)
        }
        val cs = ClientSocket()
        ch.pipeline().addLast(cs)
        cs.ch = ch
        val u = User(cs, id, name, level = 10, lucci = 1000, accountKey = name)
        u.selectedBomber = 0
        cs.users.add(u)

        val out = mutableListOf<Sent>()
        ch.pipeline().addFirst(object : ChannelOutboundHandlerAdapter() {
            override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                if (msg is PKT) {
                    val op = OpcodeManager.getSendOp(msg.javaClass)
                    val buf = Unpooled.buffer()
                    try {
                        msg.encode(SendPacketBase(buf, 1))
                        val bytes = ByteArray(buf.readableBytes())
                        buf.readBytes(bytes)
                        out.add(Sent(op, bytes))
                    } finally {
                        buf.release()
                    }
                }
                promise.setSuccess()
            }
        })
        return cs to out
    }

    private fun parseMotionBlur(pkt: PKT): Pair<Int, Int> {
        val spb = SendPacketBase(Unpooled.buffer(), 3)
        pkt.encode(spb)
        val buf = spb.buf
        return try {
            val all = ByteArray(buf.readableBytes())
            buf.readBytes(all)
            val avatarCount = (all[all.size - 1].toInt() and 0xFF) xor 0x5A
            assertEquals(0, avatarCount, "Expected 0 avatars in test account")
            val style = (all[all.size - 7].toInt() and 0xFF) xor 0x5A
            val plus = (all[all.size - 6].toInt() and 0xFF) xor 0x5A
            style to plus
        } finally {
            buf.release()
        }
    }

    @AfterEach
    fun cleanup() {
        val toClose = Lobby.members.toList()
        toClose.forEach { Lobby.leave(it) }
        Room.slots.forEach { s ->
            s.user?.c?.ch?.let { ch -> if (ch is EmbeddedChannel) ch.finishAndReleaseAll() }
            s.user = null
            s.isAI = false
            s.state = 0
        }
        toClose.forEach { c -> (c.ch as? EmbeddedChannel)?.finishAndReleaseAll() }
        Room.reset()
        Lobby.onRoomReset()
    }

    @Test
    fun `motion blur cards encode style 2, 4, 5 and revert to 0xFF on unequip`() {
        val (dummySock, _) = makeClient(88990, "MBDummy", "127.0.0.1", "127.0.0.1")
        val emptyKey = "mb_empty_${System.nanoTime()}"
        val emptyUser = User(dummySock, 88991, "MBEmpty", accountKey = emptyKey)
        assertEquals(0xFF to 0, parseMotionBlur(PracticeRoomInfo(0, emptyUser, 0)))
        assertEquals(0xFF to 0, parseMotionBlur(PracticeStartInfo(0, emptyUser, 0)))
        assertEquals(0xFF to 0, parseMotionBlur(MpPackets.SlotPlayerInfo(0, emptyUser, byteArrayOf(0, 0, 0), false)))

        val testKey = "mb_equip_${System.nanoTime()}"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(testKey.lowercase(Locale.ROOT).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val accountsDir = Path.of(System.getProperty("bnbemu.accountsDir", "data/local-accounts"))
        Files.createDirectories(accountsDir)
        val acctFile = accountsDir.resolve("$hash.properties")
        try {
            Files.writeString(
                acctFile,
                "lucci=10000\neconomy.version=1\nitem.900800002=1\nitem.900800004=1\nitem.900800005=1\n"
            )
            val user = User(dummySock, 88992, "MBTester", accountKey = testKey)
            val cases = listOf(900800002 to 2, 900800004 to 4, 900800005 to 5)
            for ((itemId, expectedStyle) in cases) {
                LocalShop.store.changeEquipment(testKey, 0, listOf(248 to itemId))
                assertEquals(expectedStyle to 0, parseMotionBlur(PracticeRoomInfo(0, user, 0)))
                assertEquals(expectedStyle to 0, parseMotionBlur(PracticeStartInfo(0, user, 0)))
                assertEquals(expectedStyle to 0, parseMotionBlur(MpPackets.SlotPlayerInfo(1, user, byteArrayOf(0, 0, 1), false)))
            }
            LocalShop.store.changeEquipment(testKey, 0, listOf(248 to 0))
            assertEquals(0xFF to 0, parseMotionBlur(PracticeStartInfo(0, user, 0)))
        } finally {
            Files.deleteIfExists(acctFile)
        }
    }

    @Test
    fun `multiplayer room guards, bomber action relay, host migration and stage 2 barrier`() {
        val id1 = allocateNextUserId()
        val id2 = allocateNextUserId()
        assertEquals(id1 + 1, id2)

        val (host, hostOut) = makeClient(81001, "HostP", "192.168.1.10", "192.168.1.1")
        val (guest, guestOut) = makeClient(81002, "GuestP", "192.168.1.11", "192.168.1.1")
        val (outsider, outOut) = makeClient(81003, "OutP", "192.168.1.12", "192.168.1.1")

        Lobby.join(host)
        Lobby.join(guest)
        Lobby.join(outsider)

        Lobby.createRoom(host, "MonsterMP", "", 14)
        Lobby.joinRoom(guest, Lobby.ROOM_NO, Lobby.ROOM_KEY, "")

        // Outsider cannot overwrite active room
        outOut.clear()
        Lobby.createRoom(outsider, "Hijack", "", 0)
        assertTrue(Lobby.isInRoom(host) && Lobby.isInRoom(guest))
        assertTrue(outOut.any { it.op == 80 })

        val badPractice = Unpooled.wrappedBuffer(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
        with(CreatePracticeSessionReq) { badPractice.handle(outsider) }
        badPractice.release()
        assertTrue(Lobby.isInRoom(host) && Lobby.isInRoom(guest))

        // Guest cannot select map or change Host's color
        Room.mapId = 89
        val mapBuf = Unpooled.wrappedBuffer(byteArrayOf(0, 0, 0, 0, 0, 1))
        with(SelectMapReq) { mapBuf.handle(guest) }
        mapBuf.release()
        assertEquals(89, Room.mapId)

        Room.slots[0].color = 0
        val colBuf = Unpooled.wrappedBuffer(byteArrayOf((0x05 xor 0x5A).toByte()))
        with(SelectColorReq) { colBuf.handle(guest) }
        colBuf.release()
        assertEquals(0, Room.slots[0].color.toInt())

        // Start Monster Mode match and verify Stage 2 barrier + BomberAction relay + Host migration
        Room.localGameMode = 14
        Room.slots[0].state = FLAG_CHIEF or 2
        Room.slots[1].state = FLAG_READY
        Room.startGame(14)
        Lobby.setInGame(true)

        // Outsider bomber action blocked; Host->Guest trap (0x21) & rescue (0x31) relayed
        hostOut.clear()
        guestOut.clear()
        val outAct = Unpooled.wrappedBuffer(byteArrayOf((0x41 xor 0x5A).toByte(), 0, 0, 0, 0))
        Lobby.onBomberAction(outAct, outsider)
        outAct.release()
        assertTrue(hostOut.none { it.op == 146 } && guestOut.none { it.op == 146 })

        val trap = Unpooled.wrappedBuffer(byteArrayOf((0x21 xor 0x5A).toByte(), 0x10, 0, 0x10, 0))
        Lobby.onBomberAction(trap, host)
        trap.release()
        val rescue = Unpooled.wrappedBuffer(byteArrayOf((0x31 xor 0x5A).toByte(), 0x10, 0, 0x10, 0))
        Lobby.onBomberAction(rescue, host)
        rescue.release()
        assertEquals(2, guestOut.count { it.op == 146 })

        // SelectBomber blocked while in-game
        val prevBomber = Room.slots[1].bomber.toInt()
        val midBomber = Unpooled.wrappedBuffer(byteArrayOf((1 xor 0x5A).toByte(), (3 xor 0x5A).toByte()))
        with(SelectBomberReq) { midBomber.handle(guest) }
        midBomber.release()
        assertEquals(prevBomber, Room.slots[1].bomber.toInt())

        // Stage 2 load barrier waits for both humans, then sends GameBoardPeers (op 136, 70B) + MonsterControl (op 178)
        Room.monsterBoardLoadPending = true
        Room.monsterBoardStartSent = false
        hostOut.clear()
        guestOut.clear()
        Lobby.handleMonsterBoardLoaded(host)
        assertTrue(hostOut.none { it.op == 136 })
        Lobby.handleMonsterBoardLoaded(guest)
        assertEquals(
            1,
            hostOut.count { it.op == 136 && it.body.size == 70 },
            "hostOut=${hostOut.map { "${it.op}:${it.body.size}" }}"
        )
        assertEquals(
            1,
            guestOut.count { it.op == 136 && it.body.size == 70 },
            "guestOut=${guestOut.map { "${it.op}:${it.body.size}" }}"
        )

        // Host disconnect migrates MonsterControl(120 pkts) to Guest and evicts UdpPeers
        UdpPeers.register(81001, InetSocketAddress("198.51.100.1", 13456), emptyList())
        guestOut.clear()
        Lobby.leave(host)
        assertEquals(1, Lobby.hostSlot)
        assertEquals(120, guestOut.count { it.op == 178 })
        assertNull(UdpPeers.observedOf(81001))
    }

    @Test
    fun `encoder cleans NetConfig ThreadLocal and idle handler closes idle channel`() {
        val encCh = object : EmbeddedChannel(Encoder()) {
            override fun localAddress(): SocketAddress = InetSocketAddress(InetAddress.getByName("10.20.30.40"), 3838)
        }
        try {
            encCh.writeOutbound(object : PKT {
                override fun encode(out: SendPacketBase) {
                    throw RuntimeException("simulated failure")
                }
            })
        } catch (_: Throwable) {
        }
        assertEquals("127.0.0.1", NetConfig.serverIp)
        encCh.finishAndReleaseAll()

        val idleCh = EmbeddedChannel()
        UdpRelay.installIdleTimeout(idleCh, 30)
        assertTrue(idleCh.isOpen)
        idleCh.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT)
        assertFalse(idleCh.isOpen)
    }

    @Test
    fun `symmetric NAT fallback and per-channel pinhole learning`() {
        val (p0, _) = makeClient(70001, "NatP0", "198.51.100.10", "172.16.0.10")
        val (p1, _) = makeClient(70002, "NatP1", "198.51.100.20", "172.16.0.10")
        Room.reset()
        Room.slots[0].user = p0.users[0]
        Room.slots[0].isAI = false
        Room.slots[1].user = p1.users[0]
        Room.slots[1].isAI = false
        UdpPeers.register(70001, InetSocketAddress("198.51.100.10", 50001), emptyList())
        UdpPeers.register(70002, InetSocketAddress("198.51.100.20", 60001), emptyList())

        val newExternal = InetSocketAddress("198.51.100.10", 50002)
        assertEquals(0, UdpRelay.slotOfSender(newExternal))
        UdpRelay.recordReturn(1, 0, 70001, newExternal)
        assertEquals(newExternal, UdpRelay.addrOfSlot(1, 0))
        UdpRelay.clearUser(70001)
        UdpRelay.clearUser(70002)
    }
}
