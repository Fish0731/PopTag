package bnbemu.handler

import bnbemu.netty.PKTHandler
import bnbemu.packet.EnterLobbyStage
import bnbemu.packet.SessionList
import bnbemu.server.Lobby
import bnbemu.server.Room

val EnterLobbyStageReq = PKTHandler { c ->
    Lobby.leaveRoom(c, isDisconnect = false)
    if (Lobby.humanCount() == 0) {
        Room.cancelPendingReturn()
    }
    Lobby.join(c)
    c.send(
        EnterLobbyStage(),
        SessionList(Lobby.sessionEntries(c)),
        Lobby.roomListPacket(),
    )
}
