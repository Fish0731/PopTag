package bnbemu.packet.room

import bnbemu.netty.PKT
import bnbemu.server.Room
import bnbemu.server.LocalEconomy
import bnbemu.server.color

private const val RESULT_WIN = 0
private const val RESULT_LOSS = 2
private const val RESULT_DRAW = 3

// S→C GameResult (Opcode 150 in EN / CA.exe 0x00559854)
// 结构 (约 119 字节):
//   1. Header (11 字节, CA.exe 0x0053E09C):
//      Decode1: gameType (1)
//      Decode1: winnerTeam (1 为 AI/红队, 0 为玩家/蓝队)
//      Decode1: unk3 (0)
//      Decode1: perfectWin (0 = normal, 1 = perfect; not the failure flag)
//      Decode1: unk5 (0)
//      Decode4: delayFrames (30 帧/秒；0x559C88 除以 30 后作为结算等待时间)
//      Decode1: unk7 (0xFF / -1 跳过子结构)
//      Decode1: unk8 (0xFF / -1)
//   2. 8 Slots (CA.exe 0x00541205):
//      Human slotType=1: 64 bytes; AI slotType=2: 6 bytes; empty: 1 byte.
//      Row +0x54/+0x5C are details; +0x98/+0xA8 are visible totals.
//      Totals are display-only copies of the same award, not additional credit.
//   3. Trailer (CA.exe 0x0053E034):
//      每 Human 槽 (slotType=1): 2 字节 (Decode1 × 2)
fun GameResult(
    winnerTeam: Int = 1,
    delayFrames: Int = 60,
    expGained: Int = 0,
    lucciGained: Int = 0,
    missionClear: Boolean = true,
    rewards: Map<Int, bnbemu.server.LocalEconomy.Reward> = emptyMap()
) = PKT {
    val draw = winnerTeam == -1
    val monsterMode = Room.localGameMode == 14
    val monsterFailed = monsterMode && winnerTeam != 0
    // 1. Header (11 字节)
    Encode1(if (monsterFailed || !missionClear) 0 else 1) // No mission-clear effect on failure.
    Encode1(winnerTeam) // The native decoder reads -1 as the signed no-winner value.
    Encode1(0)           // unk3
    Encode1(0)           // Never claim a perfect victory for a failed round.
    Encode1(0)           // unk5
    Encode4(delayFrames) // 60 frames = 2 seconds
    Encode1(0xFF)        // unk7 (-1)
    Encode1(0xFF)        // unk8 (-1)

    // 2. 8 Slots
    for (slotIndex in 0 until 8) {
        val slot = Room.slots.getOrNull(slotIndex)
        val isHuman = slot?.user != null
        val isAI = slot?.isAI == true

        if (isHuman) {
            val earnedExp = rewards[slotIndex]?.experience ?: expGained
            val earnedGold = rewards[slotIndex]?.gold ?: lucciGained
            val user = slot!!.user!!
            val before = LocalEconomy.progress((LocalEconomy.totalExperience(user.level, user.exp) - earnedExp.coerceAtLeast(0)).coerceAtLeast(0))
            Encode1(1) // slotType = 1 (Human)
            Encode4(0) // score / unk
            // The result display dispatches 0 to win, 1 to perfect win,
            // 2 to loss and 3 to the draw glyph.
            val won=if(Room.localGameMode==0) slot.color.toInt()==winnerTeam else winnerTeam==0
            Encode1(if (draw) RESULT_DRAW else if (won) RESULT_WIN else RESULT_LOSS)
            Encode1(0)
            Encode1(0)
            Encode1(0)
            Encode4(earnedExp)
            Encode1(0) // exp bonus %
            Encode4(earnedGold)
            Encode1(0) // lucci bonus %
            Encode4(0)
            Encode1(0)
            Encode4(0)
            Encode1(0)
            Encode1(0)
            Encode1(0)
            Encode2(LocalEconomy.progressRatio(before.first, before.second)) // +0x7c: animation start
            Encode2(LocalEconomy.progressRatio(user.level, user.exp)) // +0x80: animation end
            Encode2(user.level) // +0x84: room/result badge
            Encode2(user.level) // +0x88: updates CUserInfoMan local level, native 5599F0
            Encode1(0)
            Encode1(0)
            Encode1(0)
            Encode1(0)
            Encode4(0)
            Encode2(0)
            Encode4(earnedGold) // +0x98: per-player GOLD column, native 46F60C -> 53E880
            // (feature 6 disabled, skipped)
            Encode2(0)
            Encode4(0)
            Encode4(earnedGold) // +0xa8: animated coin total, native 53E6B8
            Encode1(0)
        } else if (isAI) {
            Encode1(2) // slotType = 2 (AI Bot)
            Encode1(if (draw) RESULT_DRAW else if (winnerTeam == 0) RESULT_LOSS else RESULT_WIN)
            Encode1(0)
            Encode1(0)
            Encode2(if (Room.localGameMode == 16) bnbemu.server.npcDifficultyBadgeLevel(slot!!.npcDifficulty) else slot!!.AILevel)
        } else {
            Encode1(0) // slotType = 0 (Empty)
        }
    }

    // 3. Trailer (CA.exe 0x0053E034)
    for (slotIndex in 0 until 8) {
        val slot = Room.slots.getOrNull(slotIndex)
        if (slot?.user != null) {
            Encode1(0)
            Encode1(0)
        }
    }
}
