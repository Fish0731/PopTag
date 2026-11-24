package bnbemu.handler

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.PracticeSelectMap
import bnbemu.packet.PracticeRoomInfo
import bnbemu.packet.PracticeSetPlayer
import bnbemu.packet.PracticeSlot103
import bnbemu.packet.PracticeSlot104
import bnbemu.packet.PracticeSlot114
import bnbemu.packet.PracticeSlotReady
import bnbemu.packet.PracticeUserInventory
import bnbemu.packet.PracticeUnk423
import bnbemu.packet.RoomEntry
import bnbemu.packet.RoomList
import bnbemu.packet.SessionInfoResult
import bnbemu.packet.SessionSimpleInfo
import bnbemu.packet.Unk113
import bnbemu.packet.UserSimpleInfoResult
import bnbemu.server.*

// ============================================================================
// 进练习房 (C→S op 75) — 在练习房界面点"开始"时发出
// ============================================================================
// EN op75 以两个长度前缀字符串（房名、密码）开头；历史训练样本两者为空，
// 所以前四字节曾被误认作固定整数。后接 kind、playerCount、角色列表、option、extra。
// 历史 kind=0 样本保持训练 mode15；当前命名 NPC 房 kind=1 使用 mode16。
// 完整解析与边界检查见 server/LocalBattleMode.kt。
// 服务器连发整套房间设置回应 (seq 顺序, 见 Practice.kt 注释):
//   [87 RoomList, 83 SessionSimpleInfo, 4(13B), 4(65B), 113, 100(78B), 108,
//    106×8, 每槽(114,104,105,103), 198, 423]
// 之后客户端自动跟发 297/302, 并再发 op 92 触发游戏开始 (见 StartGameReq.kt)。
// 玩家注册进 Room slot 0 (leader), 其余 7 槽设为 AI, 以便 AI 自动行动广播 (Room.BC) 能送达。
// server = C???::OnCreatePracticeSession (原始名称未知, 绑定见 EN_18_recv.yaml)
val CreatePracticeSessionReq = PKTHandler { c ->
    val request = decodeLocalRoomRequest() ?: return@PKTHandler
    if(request.characters.size != c.users.size.coerceAtMost(2)) return@PKTHandler
    // A new room must also stop the previous match and clear its running flag.
    Room.reset()
    Room.localGameMode = request.gameMode
    // Each mode starts on an appropriate concrete map, not the captured room's
    // unrelated map index (and never a random-map pseudo entry).
    val initialName = when (request.gameMode) {
        14 -> "MonPen01"
        15 -> "Patrit14" // 海盗 14 is the user's preferred training default.
        else -> "Cemetery04"
    }
    bnbemu.data.FxdynMapInfo.records.filter { it.flag == 0 }.withIndex()
        .firstOrNull { it.value.name == initialName }?.let {
            Room.mapId = it.index
            Room.themeId = it.value.sortOrder
        }
    println("[LocalBattleMode] kind=${request.kind} mode=${request.gameMode} chars=${request.characters} option=${request.option} extra=${request.extra} raw=${request.rawHex}")

    // 玩家 (op 7 已把 c.users[0] 填好) 放 slot 0, 其余 7 槽为 AI
    val player = c.users.firstOrNull()
    if (player != null) {
        Room.slots[0].apply {
            this.user = player
            isAI = false
            state = FLAG_CHIEF
            bomber = player.selectedBomber
            color = 0
        }
        for (i in 1..7) {
            Room.slots[i].apply {
                this.user = null
                isAI = request.gameMode == 16 && i >= 4
                bomber = request.aiCharacter(i)
                color = 7
                AILevel = i
                npcDifficulty = 4
                state = if (isAI || request.gameMode == 0) 0 else FLAG_CLOSED
            }
        }
    }

    // The second local player is a human seat on the same connection, never AI.
    c.users.getOrNull(1)?.let { second ->
        Room.slots[1].apply {
            user=second;isAI=false;state=0;bomber=second.selectedBomber;color=0
        }
    }

    c.send(
        RoomList(listOf(RoomEntry.local())),
        SessionSimpleInfo(gameMode = request.gameMode),
        UserSimpleInfoResult(), SessionInfoResult(c.users),
        Unk113(),
        PracticeRoomInfo(bombStyle = localBombStyle(player), user = player),
        bnbemu.packet.room.SelectMap(themeId = Room.themeId, mapId = Room.mapId),
    )
    c.users.getOrNull(1)?.let {c send PracticeRoomInfo(localBombStyle(it),it,slot=1)}
    // 106×8 (sq=208~229)
    repeat(8) { slot ->
        c.send(PracticeSlotReady(slot, if (Room.slots[slot].user != null || Room.slots[slot].isAI) 2 else if (request.gameMode == 0) 0 else 1))
    }
    // 每槽交错发送 [114, 104, 105, 103] (槽 7 无 103), 与真实线序一致
    repeat(8) { slot ->
        if (Room.slots[slot].user == null && !Room.slots[slot].isAI) {
            return@repeat   // 练习/怪物模式: 仅玩家槽位, 其余关闭
        }
        c.send(
            PracticeSlot114(slot, Room.slots[slot].user?.selectedBomber ?: request.aiCharacter(slot)),
            PracticeSlot104(slot,Room.slots[slot].user?.let { Room.slots[slot].color.toInt() }),
        )
        // The NPC strategy refreshes labels on op103, after reading the role.
        c.send(PracticeSetPlayer(slot, Room.slots[slot].user?.selectedBomber ?: request.aiCharacter(slot)))
        if (request.gameMode == 16 && slot >= 4)
            c.send(PracticeSlot103(slot, isAI = true, aiLevel = 0, flag = 4))
    }
    bnbemu.server.needleStockPackets(c.users.size).forEach { c send it }   // 先建持有物, op198 热键穿戴的前置
    c.send(localUserInventory(player, practice = true))
    if (request.gameMode == 16) c send bnbemu.packet.NpcAvailability()
    c.send(PracticeUnk423())
}
