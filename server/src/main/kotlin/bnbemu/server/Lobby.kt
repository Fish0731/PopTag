package bnbemu.server

import bnbemu.isJP
import bnbemu.netty.ClientSocket
import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.HandlerRouterUDP
import bnbemu.netty.PKT
import bnbemu.netty.PassportServer
import bnbemu.netty.UdpRelay
import bnbemu.packet.CreateSessionResult
import bnbemu.packet.JOIN_NO_ROOM
import bnbemu.packet.JOIN_REJECTED
import bnbemu.packet.JoinRoomResult
import bnbemu.packet.LeaveSlot
import bnbemu.packet.LobbyRoom
import bnbemu.packet.MpPackets
import bnbemu.packet.PracticeSlot103
import bnbemu.packet.PracticeSlot114
import bnbemu.packet.PracticeSlotReady
import bnbemu.packet.RosterEntry
import bnbemu.packet.SessionEntry
import bnbemu.packet.SessionInfoResult
import bnbemu.packet.SessionList
import bnbemu.packet.SessionSimpleInfo
import bnbemu.packet.Unk113
import bnbemu.packet.UserSimpleInfoResult
import bnbemu.packet.room.MonsterControl
import bnbemu.packet.room.SelectColor
import bnbemu.packet.room.SelectMap
import bnbemu.packet.room.SlotBomber
import bnbemu.packet.room.SlotMeta
import bnbemu.packet.room.StartGameResult
import io.netty.buffer.ByteBuf
import io.netty.channel.socket.DatagramPacket
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Coordinates cross-machine multiplayer lobby presence, single active room lifecycle,
 * P2P UDP relay endpoints, Monster Mode stage 2/3 load barriers, host migration,
 * and room-level authorization guards.
 */
object Lobby {
    const val ROOM_NO = 1
    const val ROOM_KEY = 1

    val members = CopyOnWriteArrayList<ClientSocket>()

    @Volatile var isRoomActive: Boolean = false
        private set
    @Volatile var roomTitle: String = "PopTag Room"
        private set
    @Volatile var roomPassword: String = ""
        private set
    @Volatile var hostSlot: Int = 0
        private set
    @Volatile var gameType: Int = 0
    @Volatile private var inGameFlag: Boolean = false
    @Volatile private var matchSeq: Int = 0

    private val boardAckClients = HashSet<ClientSocket>()
    private val monsterLoadedClients = HashSet<ClientSocket>()
    @Volatile private var monsterLoadedReceipt: String = ""
    private val pendingRoomCleanup = Collections.synchronizedSet(HashSet<ClientSocket>())
    private val pendingPassportLogins = ConcurrentHashMap<String, Long>()

    val isInGame: Boolean
        get() = Room.matchRunning || inGameFlag

    fun setInGame(v: Boolean) {
        inGameFlag = v
    }

    @Synchronized
    fun onRoomReset() {
        isRoomActive = false
        hostSlot = 0
        inGameFlag = false
        boardAckClients.clear()
        monsterLoadedClients.clear()
    }

    @Synchronized
    fun join(c: ClientSocket?) {
        if (c == null || c.users.isEmpty()) return
        if (members.any { it === c }) return
        members.add(c)
        c.ch?.closeFuture()?.addListener { leave(c) }
        broadcastRoster(c)
    }

    @Synchronized
    fun leave(c: ClientSocket?) {
        if (c == null) return
        pendingRoomCleanup.remove(c)
        boardAckClients.remove(c)
        monsterLoadedClients.remove(c)
        for (u in c.users) {
            UdpRelay.clearUser(u.id)
        }
        if (members.remove(c)) {
            leaveRoom(c, isDisconnect = true)
            broadcastRoster(null)
            broadcastRooms()
        }
    }

    fun onlineUsers(): List<User> = members.mapNotNull { c ->
        if (c.ch?.isActive == true && c.users.isNotEmpty()) c.users[0] else null
    }

    fun sessionEntries(self: ClientSocket): List<SessionEntry> {
        val list = ArrayList<SessionEntry>()
        val seen = HashSet<String>()
        for (u in onlineUsers()) {
            if (list.size >= LocalPlayers.MAX_SESSION_ENTRIES) break
            if (seen.add(u.name)) {
                list.add(SessionEntry(u.name, u.level, 0, 0, 0, 0))
            }
        }
        for (e in LocalPlayers.sessionEntries(self)) {
            if (list.size >= LocalPlayers.MAX_SESSION_ENTRIES) break
            if (seen.add(e.name)) list.add(e)
        }
        return list
    }

    fun rosterEntries(self: ClientSocket): List<RosterEntry> {
        val list = ArrayList<RosterEntry>()
        val seen = HashSet<String>()
        for (u in onlineUsers()) {
            if (list.size >= LocalPlayers.MAX_SESSION_ENTRIES) break
            if (seen.add(u.name)) list.add(RosterEntry(u.name, u.id))
        }
        for (e in LocalPlayers.rosterEntries(self)) {
            if (list.size >= LocalPlayers.MAX_SESSION_ENTRIES) break
            if (seen.add(e.name)) list.add(e)
        }
        return list
    }

    fun findOnlineByName(name: String?): ClientSocket? {
        if (name == null) return null
        return members.firstOrNull { c ->
            c.ch?.isActive == true && c.users.isNotEmpty() && c.users[0].name.equals(name, ignoreCase = true)
        }
    }

    fun slotOf(c: ClientSocket?): Int {
        if (c == null) return -1
        return Room.slots.indexOfFirst { it.user?.c === c }
    }

    fun isInRoom(c: ClientSocket?): Boolean = slotOf(c) >= 0

    fun humanCount(): Int = Room.slots.count { it.user != null && !it.isAI }

    fun maxPlayableSlots(): Int {
        val open = Room.slots.count { (it.state.toInt() and FLAG_CLOSED) == 0 }
        return maxOf(open, humanCount())
    }

    fun roomListPacket(): PKT {
        if (!isRoomActive || humanCount() == 0) {
            return MpPackets.RoomListOf(emptyList())
        }
        val locked = if (roomPassword.isNotEmpty()) 1 else 0
        val playing = if (isInGame) 1 else 0
        val lr = LobbyRoom(
            roomNo = ROOM_NO,
            key = ROOM_KEY,
            name = roomTitle,
            f48 = locked,
            f54 = 0,
            f6c = playing,
            f78 = 0,
            f84 = humanCount(),
            f90 = 0,
            f9c = maxPlayableSlots(),
            f60 = 16,
            fc0 = 4,
            fa8 = 0,
            fb4 = 0,
        )
        return MpPackets.RoomListOf(listOf(lr))
    }

    fun broadcastRooms() {
        val pkt = roomListPacket()
        for (c in members) {
            if (c.ch?.isActive == true && !isInRoom(c)) c.send(pkt)
        }
    }

    fun broadcastRoomsGone() {
        val pkt = MpPackets.RoomListDelta(listOf(ROOM_NO to ROOM_KEY))
        for (c in members) {
            if (c.ch?.isActive == true && !isInRoom(c)) c.send(pkt)
        }
    }

    fun broadcastRoster(except: ClientSocket?) {
        for (c in members) {
            if (c !== except && c.ch?.isActive == true && !isInRoom(c)) {
                c.send(SessionList(sessionEntries(c)))
            }
        }
    }

    fun broadcastLobbyChat(pkt: PKT) {
        for (c in members) {
            if (c.ch?.isActive == true && !isInRoom(c)) c.send(pkt)
        }
    }

    fun broadcastRoom(vararg pkts: PKT) {
        Room.BC(*pkts)
    }

    @Synchronized
    fun canCreateRoom(host: ClientSocket?): Boolean {
        if (host == null || host.users.isEmpty()) return false
        if (!isRoomActive || humanCount() == 0) return true
        return !isInGame && humanCount() == 1 && isInRoom(host)
    }

    @Synchronized
    fun createRoom(host: ClientSocket?, title: String?, password: String?, gameType: Int) {
        if (host == null || host.users.isEmpty()) return
        if (!canCreateRoom(host)) {
            if (host.ch?.isActive == true) {
                host.send(CreateSessionResult(1), roomListPacket())
            }
            return
        }
        Room.reset()
        boardAckClients.clear()
        monsterLoadedClients.clear()
        isRoomActive = true
        roomTitle = if (title.isNullOrEmpty()) "PopTag Room" else title
        roomPassword = password.orEmpty()
        hostSlot = 0
        this.gameType = gameType
        inGameFlag = false

        val u = host.users[0]
        Room.slots[0].apply {
            user = u
            isAI = false
            state = FLAG_CHIEF or 2
            bomber = u.selectedBomber
            color = 0
        }

        host.send(CreateSessionResult(0))
        Room.encodeSlots(host)
        broadcastRooms()
    }

    @Synchronized
    fun joinRoom(c: ClientSocket?, reqNo: Int, reqKey: Int, password: String?) {
        if (c == null || c.users.isEmpty()) return
        if (!isRoomActive || humanCount() == 0 || isInGame) {
            c.send(JoinRoomResult(JOIN_NO_ROOM, 0, 0, 0))
            return
        }
        if (roomPassword.isNotEmpty() && roomPassword != password) {
            c.send(JoinRoomResult(JOIN_REJECTED, 0, 0, 0))
            return
        }
        if (isInRoom(c)) return

        val slots = Room.slots
        val freeIdx = slots.indexOfFirst { s ->
            s.user == null && !s.isAI && (s.state.toInt() and FLAG_CLOSED) == 0
        }
        if (freeIdx < 0) {
            c.send(JoinRoomResult(JOIN_REJECTED, 0, 0, 0))
            return
        }

        val u = c.users[0]
        val target = slots[freeIdx]
        target.user = u
        target.isAI = false
        target.state = 0
        target.bomber = u.selectedBomber
        target.color = freeIdx

        c.send(JoinRoomResult(0, ROOM_NO, ROOM_KEY, gameType))
        c.send(SessionInfoResult(), Unk113())

        for (i in slots.indices) {
            val su = slots[i].user
            if (su != null && !slots[i].isAI) {
                c.send(MpPackets.SlotPlayerInfo(i, su, slots[i].meta, true))
            }
        }
        c.send(SelectMap(0, Room.themeId, Room.mapId))
        for (i in slots.indices) {
            val st = slots[i].state.toInt()
            val slotMode = if (slots[i].isAI) 2 else if ((st and FLAG_CLOSED) != 0) 1 else 0
            c.send(PracticeSlotReady(i, slotMode))
        }
        for (i in slots.indices) {
            val s = slots[i]
            val bomber = s.bomber.toInt()
            val color = s.color.toInt()
            c.send(
                PracticeSlot114(i, if (s.isAI && gameType == 16) bomber else 5),
                SelectColor(i, color, false, 0, 0, 0),
                SlotBomber(i, bomber),
            )
            if (s.isAI) {
                c.send(PracticeSlot103(i, true, hostSlot, s.AILevel))
            } else if (s.user != null) {
                c.send(SlotMeta(i, s.meta.copyOf()))
            }
        }

        for (i in slots.indices) {
            val su = slots[i].user
            if (i != freeIdx && su != null && !slots[i].isAI && su.c.ch?.isActive == true) {
                su.c.send(
                    MpPackets.SlotPlayerInfo(freeIdx, u, target.meta, true),
                    SelectColor(freeIdx, freeIdx, false, 0, 0, 0),
                    SlotBomber(freeIdx, u.selectedBomber),
                    SlotMeta(freeIdx, target.meta.copyOf()),
                )
            }
        }
        broadcastRooms()
    }

    @Synchronized
    fun leaveRoom(c: ClientSocket?, isDisconnect: Boolean) {
        val idx = slotOf(c)
        if (idx < 0 || c == null) return

        val slots = Room.slots
        val leavingUserId = slots[idx].user?.id ?: 0
        if (!isInGame) {
            if (idx == hostSlot) {
                for (i in slots.indices) {
                    val u = slots[i].user
                    if (u != null && u.c !== c && u.c.ch?.isActive == true) {
                        u.c.send(LeaveSlot(i, u.id, 1))
                    }
                    slots[i].user = null
                    slots[i].isAI = false
                    slots[i].state = 0
                }
                boardAckClients.clear()
                monsterLoadedClients.clear()
                isRoomActive = false
                Room.reset()
                broadcastRoomsGone()
            } else {
                slots[idx].user = null
                slots[idx].isAI = false
                slots[idx].state = 0
                if (!isDisconnect && c.ch?.isActive == true) {
                    c.send(LeaveSlot(idx, leavingUserId, 0))
                }
                Room.BC(LeaveSlot(idx, leavingUserId, 0))
                if (humanCount() == 0) {
                    boardAckClients.clear()
                    monsterLoadedClients.clear()
                    isRoomActive = false
                    Room.reset()
                    broadcastRoomsGone()
                } else {
                    broadcastRooms()
                }
            }
        } else {
            boardAckClients.remove(c)
            monsterLoadedClients.remove(c)
            Room.onBomberDie(idx)
            slots[idx].user = null
            slots[idx].isAI = false
            slots[idx].state = 0
            Room.BC(LeaveSlot(idx, leavingUserId, 0))
            if (idx == hostSlot) {
                val nextHost = slots.indexOfFirst { s ->
                    s.user != null && !s.isAI && s.user?.c?.ch?.isActive == true
                }
                if (nextHost >= 0) {
                    hostSlot = nextHost
                    slots[nextHost].state = slots[nextHost].state.toInt() or FLAG_CHIEF
                    Room.BC(SlotMeta(nextHost, slots[nextHost].meta.copyOf()))
                    if (Room.localGameMode == 14) {
                        repeat(120) { mi -> Room.BC(MonsterControl(mi, nextHost)) }
                        println("[Monster] Migrated monster control to new host slot=$nextHost")
                    }
                }
            }
            if (humanCount() == 0) {
                boardAckClients.clear()
                monsterLoadedClients.clear()
                Room.endGame()
                Room.reset()
                inGameFlag = false
                isRoomActive = false
                broadcastRoomsGone()
            } else {
                recheckLoadingBarriersLocked()
                broadcastRooms()
            }
        }
    }

    @Synchronized
    fun kickSlot(requester: ClientSocket?, targetSlot: Int) {
        if (slotOf(requester) != hostSlot || targetSlot == hostSlot) return
        val slots = Room.slots
        if (targetSlot !in slots.indices) return
        val u = slots[targetSlot].user ?: return
        val kickedUserId = u.id
        val targetSock = u.c
        slots[targetSlot].user = null
        slots[targetSlot].isAI = false
        slots[targetSlot].state = 0
        if (targetSock.ch?.isActive == true) {
            targetSock.send(LeaveSlot(targetSlot, kickedUserId, 1))
        }
        Room.BC(LeaveSlot(targetSlot, kickedUserId, 0))
        if (humanCount() == 0) {
            isRoomActive = false
            Room.reset()
            broadcastRoomsGone()
        } else {
            broadcastRooms()
        }
    }

    @Synchronized
    fun onStageInit(c: ClientSocket?) {
        if (c == null) return
        if (isInRoom(c)) {
            pendingRoomCleanup.add(c)
            leaveRoom(c, isDisconnect = false)
        }
    }

    @Synchronized
    fun onJoinSession(c: ClientSocket?) {
        if (c == null) return
        join(c)
        val needCleanup = pendingRoomCleanup.remove(c)
        if (needCleanup && (!isRoomActive || humanCount() == 0) && c.ch?.isActive == true) {
            c.send(MpPackets.RoomListDelta(listOf(ROOM_NO to ROOM_KEY)))
        }
    }

    @Synchronized
    fun handlePracticeRoomCreated(host: ClientSocket?, unk423: PKT?) {
        if (host != null && unk423 != null) host.send(unk423)
        val mode = Room.localGameMode
        if (mode == 15 || host == null || host.users.isEmpty()) {
            isRoomActive = false
            return
        }
        val u = host.users[0]
        val slots = Room.slots
        if (slots.isNotEmpty() && slots[0].user === u) {
            slots[0].state = FLAG_CHIEF or 2
            host.send(SlotMeta(0, slots[0].meta.copyOf()))
        }
        val openUntil = if (mode == 14 || mode == 16) 4 else 8
        for (i in 1 until minOf(openUntil, slots.size)) {
            if (slots[i].user == null && !slots[i].isAI) {
                slots[i].state = 0
                host.send(PracticeSlotReady(i, 0))
            }
        }
        isRoomActive = true
        roomTitle = "${u.name}'s Room"
        roomPassword = ""
        hostSlot = 0
        gameType = mode
        inGameFlag = false
        broadcastRooms()
    }

    @Synchronized
    fun sendPostMatchRoomSnapshot(c: ClientSocket?) {
        if (c == null || c.ch?.isActive != true) return
        val slots = Room.slots
        c.send(
            SessionSimpleInfo(Room.localGameMode),
            UserSimpleInfoResult(),
            SessionInfoResult(),
            Unk113(),
            SelectMap(0, Room.themeId, Room.mapId),
        )
        for (i in slots.indices) {
            val su = slots[i].user
            if (su != null && !slots[i].isAI) {
                val st = slots[i].state.toInt()
                val fixedSt = if (i == hostSlot) st or FLAG_CHIEF else st and (FLAG_CHIEF or FLAG_READY).inv()
                if (fixedSt != st) slots[i].state = fixedSt
                c.send(MpPackets.SlotPlayerInfo(i, su, slots[i].meta, true))
            }
        }
        for (i in slots.indices) {
            val st = slots[i].state.toInt()
            val slotMode = if (slots[i].isAI) 2 else if ((st and FLAG_CLOSED) != 0) 1 else 0
            c.send(PracticeSlotReady(i, slotMode))
        }
        for (i in slots.indices) {
            val s = slots[i]
            val bomber = s.bomber.toInt()
            val color = s.color.toInt()
            c.send(
                PracticeSlot114(i, if (s.isAI && gameType == 16) bomber else 5),
                SelectColor(i, color, false, 0, 0, 0),
                SlotBomber(i, bomber),
            )
            if (s.isAI) {
                c.send(PracticeSlot103(i, true, hostSlot, s.AILevel))
            } else if (s.user != null) {
                c.send(SlotMeta(i, s.meta.copyOf()))
            }
        }
    }

    fun onBomberAction(buf: ByteBuf, c: ClientSocket) {
        try {
            val readable = buf.readableBytes()
            if (readable < 5) return
            val senderSlot = slotOf(c)
            val isMp = isRoomActive && humanCount() > 1
            if (isMp && (!isInGame || senderSlot < 0)) return

            val rawLen = minOf(readable, 512)
            val rawPayload = ByteArray(rawLen)
            buf.getBytes(buf.readerIndex(), rawPayload)

            val actionSlot = buf.Decode1
            val slotId = actionSlot and 0x0F
            val action = actionSlot and 0xF0
            val posX = buf.Decode2
            val posY = buf.Decode2

            val slots = Room.slots
            if (slotId !in slots.indices) return
            if (isMp && slots[slotId].user == null) return

            val isSelfOrHostAi = (slotId == senderSlot) || (senderSlot == hostSlot && slots[slotId].isAI)
            val su = slots[slotId].user
            if (su != null && su.c === c && posX in 0..599 && posY in 0..599) {
                slots[slotId].gridX = posX / 40
                slots[slotId].gridY = posY / 40
            }

            if (action == 0x60) {
                if (isMp && !isSelfOrHostAi) return
                Room.onLastTeamStanding(slotId)
            } else {
                if (action == 0x40 && buf.readableBytes() >= 2) {
                    buf.Decode1
                    val count = buf.Decode1.coerceIn(0, 16)
                    repeat(count) {
                        if (buf.readableBytes() >= 4) {
                            buf.Decode1; buf.Decode1; buf.Decode2
                        }
                    }
                }
                if (isInGame && senderSlot >= 0) {
                    val relay = MpPackets.BomberActionBroadcast(rawPayload)
                    for (s in slots) {
                        val u = s.user
                        if (u != null && !s.isAI && u.c !== c && u.c.ch?.isActive == true) {
                            u.c.send(relay)
                        }
                    }
                }
            }
        } finally {
            if (buf.readableBytes() > 0) buf.skipBytes(buf.readableBytes())
        }
    }

    fun checkMultiplayerStartAllowed(c: ClientSocket, replenish: Boolean): Boolean {
        if (!replenish && isRoomActive && humanCount() > 1) {
            if (slotOf(c) != hostSlot) {
                if (c.ch?.isActive == true) c.send(StartGameResult(2))
                return false
            }
            for (i in Room.slots.indices) {
                if (i == hostSlot) continue
                val s = Room.slots[i]
                if (s.user != null && !s.isAI && (s.state.toInt() and FLAG_READY) == 0) {
                    if (c.ch?.isActive == true) c.send(StartGameResult(3))
                    return false
                }
            }
        }
        return true
    }

    @Synchronized
    fun broadcastMatchStart(initiator: ClientSocket, vararg pkts: PKT) {
        inGameFlag = true
        boardAckClients.clear()
        matchSeq++
        if (humanCount() <= 1) {
            initiator.send(*pkts)
            broadcastRooms()
            return
        }
        val slots = Room.slots
        val playerInfos = ArrayList<PKT>()
        for (i in slots.indices) {
            val su = slots[i].user
            if (su != null && !slots[i].isAI) {
                playerInfos.add(MpPackets.SlotPlayerInfo(i, su, slots[i].meta, false))
            }
        }
        val mode = Room.localGameMode
        val timeLimitSec = when (mode) {
            14 -> 120
            15 -> 0
            else -> 180
        }
        for (i in slots.indices) {
            val su = slots[i].user ?: continue
            if (slots[i].isAI || su.c.ch?.isActive != true) continue
            val client = su.c
            if (client !== initiator) {
                needleStockPackets().forEach { client.send(it) }
                client.send(localUserInventory(su, practice = false, inGame = true))
            }
            val peersPkt = MpPackets.GameBoardPeers(buildPeerMapFor(client), 5, 10, timeLimitSec)
            val out = ArrayList<PKT>()
            if (pkts.isNotEmpty()) out.add(pkts[0])
            if (pkts.size > 1) out.add(pkts[1])
            out.addAll(playerInfos)
            out.add(peersPkt)
            client.send(*out.toTypedArray())
        }
        broadcastRooms()
    }

    private fun buildPeerMapFor(client: ClientSocket): Map<Int, List<UdpPeers.Endpoint>> {
        val peerMap = LinkedHashMap<Int, List<UdpPeers.Endpoint>>()
        for (j in Room.slots.indices) {
            val ju = Room.slots[j].user
            if (ju != null && !Room.slots[j].isAI) {
                peerMap[j] = listOf(UdpRelay.endpointForClient(j, client))
            }
        }
        return peerMap
    }

    @Synchronized
    fun handleMonsterBoardLoaded(c: ClientSocket?) {
        if (c == null || Room.localGameMode != 14) return
        if (!Room.monsterBoardLoadPending || Room.monsterBoardStartSent) return
        if (!isRoomActive || humanCount() <= 1) {
            Room.onMonsterBoardLoaded(c)
            Room.onBoardReady()
            return
        }
        if (slotOf(c) < 0) return
        val receipt = Room.economyReceipt
        if (receipt != monsterLoadedReceipt) {
            monsterLoadedClients.clear()
            monsterLoadedReceipt = receipt
        }
        val firstAck = monsterLoadedClients.isEmpty()
        monsterLoadedClients.add(c)
        var allAcked = true
        var activeHumans = 0
        for (s in Room.slots) {
            val u = s.user
            if (u != null && !s.isAI && u.c.ch?.isActive == true) {
                activeHumans++
                if (u.c !in monsterLoadedClients) allAcked = false
            }
        }
        if (activeHumans > 0 && allAcked) {
            completeMonsterBoardLoadedLocked(receipt)
            return
        }
        if (firstAck) {
            c.ch?.eventLoop()?.schedule({ triggerMonsterBoardFallback(receipt) }, 1500, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    private fun triggerMonsterBoardFallback(expectedReceipt: String) {
        if (!isInGame || !isRoomActive || Room.localGameMode != 14) return
        if (expectedReceipt == Room.economyReceipt && Room.monsterBoardLoadPending && !Room.monsterBoardStartSent) {
            completeMonsterBoardLoadedLocked(expectedReceipt)
        }
    }

    private fun completeMonsterBoardLoadedLocked(receipt: String) {
        if (!Room.monsterBoardLoadPending || Room.monsterBoardStartSent) return
        if (receipt != Room.economyReceipt) return
        Room.monsterBoardStartSent = true
        Room.monsterBombContext = 2
        boardAckClients.clear()
        monsterLoadedClients.clear()
        val timeLimitSec = when (Room.localGameMode) {
            14 -> 120
            15 -> 0
            else -> 180
        }
        for (s in Room.slots) {
            val su = s.user ?: continue
            if (!s.isAI && su.c.ch?.isActive == true) {
                su.c.send(MpPackets.GameBoardPeers(buildPeerMapFor(su.c), 5, 10, timeLimitSec))
            }
        }
        Room.onBoardReady()
    }

    @Synchronized
    fun onBomberActionAck(c: ClientSocket?) {
        if (c == null || !isInGame) return
        if (!isRoomActive || humanCount() <= 1) {
            Room.onBoardReady()
            return
        }
        if (slotOf(c) < 0) return
        val firstAck = boardAckClients.isEmpty()
        boardAckClients.add(c)
        var allAcked = true
        var activeHumans = 0
        for (s in Room.slots) {
            val u = s.user
            if (u != null && !s.isAI && u.c.ch?.isActive == true) {
                activeHumans++
                if (u.c !in boardAckClients) allAcked = false
            }
        }
        if (activeHumans > 0 && allAcked) {
            Room.onBoardReady()
            return
        }
        if (firstAck) {
            val seq = matchSeq
            c.ch?.eventLoop()?.schedule({ triggerBoardReadyFallback(seq) }, 1500, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    private fun triggerBoardReadyFallback(seq: Int) {
        if (seq == matchSeq && isInGame) Room.onBoardReady()
    }

    private fun recheckLoadingBarriersLocked() {
        if (!isInGame || !isRoomActive) return
        if (boardAckClients.isNotEmpty()) {
            var allAcked = true
            var activeHumans = 0
            for (s in Room.slots) {
                val u = s.user
                if (u != null && !s.isAI && u.c.ch?.isActive == true) {
                    activeHumans++
                    if (u.c !in boardAckClients) allAcked = false
                }
            }
            if (activeHumans > 0 && allAcked) Room.onBoardReady()
        }
        if (Room.localGameMode == 14 && monsterLoadedClients.isNotEmpty() &&
            Room.monsterBoardLoadPending && !Room.monsterBoardStartSent
        ) {
            var allAcked = true
            var activeHumans = 0
            for (s in Room.slots) {
                val u = s.user
                if (u != null && !s.isAI && u.c.ch?.isActive == true) {
                    activeHumans++
                    if (u.c !in monsterLoadedClients) allAcked = false
                }
            }
            if (activeHumans > 0 && allAcked) completeMonsterBoardLoadedLocked(monsterLoadedReceipt)
        }
    }

    @Synchronized
    fun canHostModifyRoom(c: ClientSocket?): Boolean {
        if (c == null) return false
        if (!isRoomActive) return true
        if (isInGame) return false
        val idx = slotOf(c)
        return idx >= 0 && idx == hostSlot
    }

    @Synchronized
    fun canSelectColor(c: ClientSocket?, reqSlot: Int): Boolean {
        if (c == null || reqSlot !in Room.slots.indices) return false
        if (!isRoomActive) return true
        if (isInGame) return false
        val me = slotOf(c)
        if (me < 0) return false
        return reqSlot == me || (me == hostSlot && Room.slots[reqSlot].isAI)
    }

    fun broadcastAiSlotClosedToOthers(host: ClientSocket, slotIdx: Int) {
        val pkts = arrayOf(PracticeSlotReady(slotIdx, 1), PracticeSlot103(slotIdx, false, 0, 0))
        for (s in Room.slots) {
            val u = s.user
            if (u != null && !s.isAI && u.c !== host && u.c.ch?.isActive == true) {
                u.c.send(*pkts)
            }
        }
    }

    private fun isSessionNameTaken(candidate: String): Boolean {
        for (m in members) {
            if (m.ch?.isActive == true) {
                if (m.users.any { it.name.equals(candidate, ignoreCase = true) }) return true
            }
        }
        if (Room.slots.any { !it.isAI && it.user?.name?.equals(candidate, ignoreCase = true) == true }) return true
        val ts = pendingPassportLogins[candidate.lowercase(Locale.ROOT)]
        return ts != null && (System.currentTimeMillis() - ts) < 5000L
    }

    @Synchronized
    fun allocateUniqueLoginName(rawName: String?): String {
        val now = System.currentTimeMillis()
        pendingPassportLogins.entries.removeIf { (now - it.value) >= 5000L }
        val base = rawName?.trim()?.takeIf { it.isNotBlank() } ?: "kcworms1"
        if (!isSessionNameTaken(base)) {
            pendingPassportLogins[base.lowercase(Locale.ROOT)] = now
            return base
        }
        for (n in 2 until 100) {
            val suffix = "_$n"
            val maxPrefix = maxOf(1, 12 - suffix.length)
            val prefix = if (base.length > maxPrefix) base.substring(0, maxPrefix) else base
            val candidate = prefix + suffix
            if (!isSessionNameTaken(candidate)) {
                pendingPassportLogins[candidate.lowercase(Locale.ROOT)] = now
                return candidate
            }
        }
        return base
    }

    fun allocateUniqueUserId(chosenName: String, preferredId: Int): Int {
        var id = maxOf(1000, preferredId)
        while (true) {
            var used = PassportServer.ACCOUNTS.values.any {
                it.userId == id && !it.username.equals(chosenName, ignoreCase = true)
            }
            if (!used) {
                used = members.any { m ->
                    m.ch?.isActive == true && m.users.any { it.id == id && !chosenName.equals(it.name, ignoreCase = true) }
                }
            }
            if (!used) return id
            id++
        }
    }

    fun evictOfflinePassportAccountsIfNeeded() {
        val map = PassportServer.ACCOUNTS
        if (map.size <= 2000) return
        val active = HashSet<String>()
        for (m in members) {
            if (m.ch?.isActive == true) {
                m.users.forEach { active.add(it.name.lowercase(Locale.ROOT)) }
            }
        }
        Room.slots.forEach { s -> s.user?.name?.let { active.add(it.lowercase(Locale.ROOT)) } }
        map.keys.removeIf { it !in active }
    }

    fun onUdpDatagram(packet: DatagramPacket?) {
        val buf = packet?.content() ?: return
        val ridx = buf.readerIndex()
        val rem = buf.readableBytes()
        val hdr = 1 + if (isJP) 4 else 0
        if (rem < hdr + 1) return
        val len = buf.getUnsignedByte(ridx).toInt()
        if (rem - 1 < len) return
        val op = buf.getUnsignedByte(ridx + hdr).toInt()
        if (op != 0) return
        val p0 = ridx + hdr + 1
        val crcLen = HandlerRouterUDP.crcLenUDP
        if (rem - (hdr + 1) < 23 + crcLen) return
        val uid = buf.getInt(p0 + 1)
        val eps = listOf(
            UdpPeers.Endpoint(buf.getInt(p0 + 11), buf.getUnsignedShort(p0 + 15)),
            UdpPeers.Endpoint(buf.getInt(p0 + 17), buf.getUnsignedShort(p0 + 21)),
        )
        UdpPeers.register(uid, packet.sender(), eps)
    }
}
