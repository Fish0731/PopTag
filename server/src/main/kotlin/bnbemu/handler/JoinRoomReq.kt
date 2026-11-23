package bnbemu.handler

import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.DecodeStr
import bnbemu.netty.PKTHandler
import bnbemu.server.Lobby

val JoinRoomReq = PKTHandler { c ->
    if (readableBytes() < 6) return@PKTHandler
    val roomNo = Decode2
    val key = Decode4
    val password = if (readableBytes() >= 2 && getUnsignedByte(readerIndex()).toInt() == 165) {
        DecodeStr
    } else {
        ""
    }
    Lobby.joinRoom(c, roomNo, key, password)
}
