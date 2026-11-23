package bnbemu.handler

import bnbemu.netty.PKTHandler
import bnbemu.server.Lobby

val QuickPlayReq = PKTHandler { c ->
    Lobby.joinRoom(c, Lobby.ROOM_NO, Lobby.ROOM_KEY, "")
}
