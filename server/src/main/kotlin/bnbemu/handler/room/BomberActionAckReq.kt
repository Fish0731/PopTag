package bnbemu.handler.room

import bnbemu.netty.PKTHandler
import bnbemu.server.Lobby

val BomberActionAckReq = PKTHandler { c ->
    Lobby.onBomberActionAck(c)
}
