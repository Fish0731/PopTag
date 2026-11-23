package bnbemu.handler

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.packet.ChatShow
import bnbemu.server.Lobby

val ChatMsg = PKTHandler { c ->
    if (readableBytes() < 4) return@PKTHandler
    val chatType = Decode1
    Decode1
    val len = Decode2
    if (len < 0 || readableBytes() < len) return@PKTHandler
    val raw = ByteArray(len)
    readBytes(raw)
    val pkt = ChatShow(raw, chatType)
    if (Lobby.isInRoom(c)) {
        Lobby.broadcastRoom(pkt)
    } else {
        Lobby.broadcastLobbyChat(pkt)
    }
}
