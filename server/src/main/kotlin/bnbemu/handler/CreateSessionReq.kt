package bnbemu.handler

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.DecodeStr
import bnbemu.netty.PKTHandler
import bnbemu.server.Lobby

val CreateSessionReq = PKTHandler { c ->
    if (readableBytes() == 6) {
        Decode2
        Decode4
        return@PKTHandler
    }
    val title = DecodeStr
    val password = DecodeStr
    val gameType = Decode1
    val slotCount = Decode1
    repeat(slotCount) {
        if (readableBytes() >= 1) Decode1
    }
    Lobby.createRoom(c, title, password, gameType)
}
