package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.server.Lobby
import bnbemu.server.Room

val KickPlayerReq = PKTHandler { c ->
    if (readableBytes() < 5) return@PKTHandler
    val targetUserId = Decode4
    Decode1
    val idx = Room.slots.indexOfFirst { it.user?.id == targetUserId }
    if (idx >= 0) {
        Lobby.kickSlot(c, idx)
    }
}
