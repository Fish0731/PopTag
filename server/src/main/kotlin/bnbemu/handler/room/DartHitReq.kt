package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// EN C108 from the native dart collision path: bomb ID, row, column, impact delay.
val DartHitReq = PKTHandler { client ->
    if (readableBytes() != 6) return@PKTHandler
    val bombId = Decode2
    val row = Decode1
    val column = Decode1
    val delayMs = Decode2
    Room.onDartHit(client, bombId, row, column, delayMs)
}
