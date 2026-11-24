package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// C124: the client has selected a destructible tile in front of the player (46636B).
val UseDrillReq = PKTHandler { client ->
    if (readableBytes() != 3) return@PKTHandler
    val row = Decode1
    val column = Decode1
    val actorAndDirection = Decode1
    Room.onUseDrill(client, row, column, actorAndDirection ushr 4, actorAndDirection and 0x0f)
}
