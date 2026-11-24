package bnbemu.netty

import bnbemu.data.FxdynMapInfo
import bnbemu.handler.room.launchLocalMatch
import bnbemu.handler.room.localMapSeatLimit
import bnbemu.handler.room.mapIdTable
import bnbemu.server.LocalMapBounds
import bnbemu.server.Room
import bnbemu.server.User
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DungeonMapsTest {
    @Test fun everyDungeonBoardHasAUniquePlayableEntryAndNativeResourceIndex() {
        val rows=FxdynMapInfo.gameRecords.filter {it.flag==0 && it.group=="Tomb"}
        assertEquals(23,rows.size)
        val native=javaClass.getResourceAsStream("/data/ported-native-map-indices.tsv")!!.bufferedReader().useLines {
            it.associate {line -> line.substringAfter('\t') to line.substringBefore('\t').toInt()}
        }
        for(i in 1..22) {
            val name="Tomb"+i.toString().padStart(2,'0')
            val row=rows.single {it.name==name}
            assertEquals(native[name],row.sortOrder)
            assertEquals("Tomb",row.stage)
            assertEquals(0,row.subtype)
            assertEquals(1,mapIdTable.values.count {it.name==name})
            assertEquals(15,LocalMapBounds.forBoard(name)?.width)
            assertEquals(13,LocalMapBounds.forBoard(name)?.height)
            assertEquals("地牢"+i.toString().padStart(2,'0'),FxdynMapInfo.localizedRecords.single {it.flag==0 && it.name==name}.display)
        }
        val group=rows.single {it.name=="TombRandom"}
        assertEquals("none",group.stage)
        assertEquals(1,group.subtype)
    }
    @Test fun cemetery19KeepsFourNpcsAndResolvesRandomOnlyInTheMatch() {
        OpcodeManager.recvOps
        val client = ClientSocket()
        val channel = EmbeddedChannel(client)
        val previousMode = Room.localGameMode
        val occupants = Room.slots.map { Triple(it.user, it.isAI, it.meta.copyOf()) }
        try {
            Room.localGameMode = 16
            Room.slots.forEach { it.user = null; it.isAI = false }
            Room.slots[0].user = User(client, 29153, "small-board")
            Room.slots[0].meta[1] = 13
            (4..7).forEach { Room.slots[it].isAI = true }
            val board = mapIdTable.entries.single { it.value.name == "Cemetery19" }
            bnbemu.handler.room.fitLocalMapNpcSeats(board.key)
            assertTrue(Room.slots[7].isAI)
            launchLocalMatch(client, board.key, board.value.themeId)
            val packets = generateSequence { channel.readOutbound<PKT>() }.toList()
            val start = packets.single { OpcodeManager.getSendOp(it::class.java) == 112 }
            val body = Unpooled.buffer()
            try {
                start.encode(SendPacketBase(body, 3))
                body.skipBytes(6)
                body.skipBytes(body.Decode2)
                body.skipBytes(6)
                assertEquals(5, body.Decode1)
                val roster = (0 until 5).map {
                    val slot = body.Decode1
                    val spawn = body.Decode1
                    val bomber = body.Decode1
                    body.skipBytes(14)
                    if (slot == 0) assertTrue(bomber in 0..7)
                    slot to spawn
                }
                assertEquals(listOf(0, 4, 5, 6, 7), roster.map { it.first })
                assertEquals(listOf(2, 3, 4, 5, 7), roster.map { it.second })
                assertEquals(13, Room.slots[0].meta[1].toInt())
                assertTrue(Room.slots[7].isAI)
            } finally { body.release() }
            for ((id, map) in mapIdTable.filterValues { it.name.matches(Regex("Tomb[0-9]{2}")) }) {
                assertTrue(localMapSeatLimit(id) >= 6, map.name)
                bnbemu.handler.room.fitLocalMapNpcSeats(id)
                assertTrue(Room.slots[7].isAI, map.name)
            }
        } finally {
            Room.reset()
            Room.localGameMode = previousMode
            occupants.forEachIndexed { index, (user, ai, meta) ->
                Room.slots[index].apply {
                    this.user = user
                    isAI = ai
                    meta.copyInto(this.meta)
                }
            }
            Room.resetMatchState()
            channel.finishAndReleaseAll()
        }
    }

    @Test fun hauntedBoardsActivateOneNativeGhostAfterLoading() {
        val client = ClientSocket()
        val channel = EmbeddedChannel(client)
        val mode = Room.localGameMode
        val player = Room.slots[0].user
        try {
            Room.slots[0].user = User(client, 29150, "ghost-board-test")
            Room.localGameMode = 15
            val boards = listOf("Tomb02" to (8 to 7), "Cemetery01" to (7 to 6), "Patrit14" to null)
            for ((name, spawn) in boards) {
                Room.startGame(mapIdTable.entries.first { it.value.name == name }.key)
                Room.checkMapGhostSpawn(Long.MAX_VALUE)
                assertNull(channel.readOutbound<PKT>())
                Room.onBoardReady()
                Room.checkMapGhostSpawn()
                assertNull(channel.readOutbound<PKT>(), "Leave time for players to move away from spawn")
                Room.checkMapGhostSpawn(Long.MAX_VALUE)
                val ghost = channel.readOutbound<PKT>()
                if (spawn == null) {
                    assertNull(ghost)
                } else {
                    assertNotNull(ghost)
                    assertEquals(158, OpcodeManager.getSendOp(ghost::class.java))
                    val body = Unpooled.buffer()
                    try {
                        ghost.encode(SendPacketBase(body, 3))
                        assertEquals(0, body.Decode1)
                        assertEquals(0x80, body.Decode1)
                        assertEquals(spawn.second, body.Decode1)
                        assertEquals(spawn.first, body.Decode1)
                        assertFalse(body.isReadable)
                    } finally { body.release() }
                }
                Room.onBoardReady()
                Room.checkMapGhostSpawn(Long.MAX_VALUE)
                assertNull(channel.readOutbound<PKT>(), "One ghost per round, including duplicate ready reports")
            }
        } finally {
            Room.reset()
            Room.localGameMode = mode
            Room.slots[0].user = player
            Room.resetMatchState()
            channel.finishAndReleaseAll()
        }
    }
    @Test fun drillReturnsTheValidatedTileAndSecondPlayersDirection() {
        val client = ClientSocket()
        val channel = EmbeddedChannel(client)
        val mode = Room.localGameMode
        val players = Room.slots.take(2).map { it.user }
        try {
            Room.slots[0].user = User(client, 29151, "drill-player-one")
            Room.slots[1].user = User(client, 29152, "drill-player-two")
            Room.localGameMode = 0
            Room.startGame(mapIdTable.entries.first { it.value.name == "Cemetery01" }.key)

            fun send(row: Int, column: Int, actorAndDirection: Int) {
                val body = Unpooled.buffer()
                SendPacketBase(body, 3).apply {
                    Encode2(124)
                    Encode1(row); Encode1(column); Encode1(actorAndDirection)
                }
                channel.writeInbound(body)
            }

            send(13, 14, 0x12)
            send(12, 15, 0x12)
            send(12, 14, 0x82)
            send(12, 14, 0x15)
            assertNull(channel.readOutbound<PKT>())
            send(12, 14, 0x12)
            val result = channel.readOutbound<PKT>()
            assertNotNull(result)
            assertEquals(156, OpcodeManager.getSendOp(result::class.java))
            val body = Unpooled.buffer()
            try {
                result.encode(SendPacketBase(body, 3))
                assertEquals(12, body.Decode1)
                assertEquals(14, body.Decode1)
                assertEquals(0x12, body.Decode1)
                assertFalse(body.isReadable)
            } finally { body.release() }
            assertNull(channel.readOutbound<PKT>(), "One shared reply for the two local seats")
            Room.endGame()
            send(12, 14, 0x12)
            assertNull(channel.readOutbound<PKT>())
        } finally {
            Room.reset()
            Room.localGameMode = mode
            players.forEachIndexed { slot, player -> Room.slots[slot].user = player }
            Room.resetMatchState()
            channel.finishAndReleaseAll()
        }
    }
}
