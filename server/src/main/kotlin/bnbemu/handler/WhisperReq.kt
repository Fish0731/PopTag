package bnbemu.handler

import bnbemu.ACP
import bnbemu.netty.DecodeStr
import bnbemu.netty.PKTHandler
import bnbemu.packet.WhisperShow
import bnbemu.server.Lobby

val WhisperReq = PKTHandler { c ->
    if (readableBytes() < 4) return@PKTHandler
    val targetName = DecodeStr
    val msg = DecodeStr
    val senderName = c.users.firstOrNull()?.name ?: "?"
    val target = Lobby.findOnlineByName(targetName) ?: return@PKTHandler
    val text = "$senderName -> $targetName : $msg"
    val pkt = WhisperShow(text.toByteArray(ACP))
    c.send(pkt)
    if (target !== c) {
        target.send(pkt)
    }
}
