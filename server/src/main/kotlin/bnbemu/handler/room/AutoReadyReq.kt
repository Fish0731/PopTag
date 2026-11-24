package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.AutoReadyResult
import bnbemu.server.Room

// EN C84 holds the room stage until it receives S304. The one-byte ready
// variant used by the LAN fork is distinct from this five-byte request.
val AutoReadyReq = PKTHandler { client ->
    if (readableBytes() != 5) return@PKTHandler
    val requestedState = Decode1
    Decode4 // Client request context. The native S304 reader consumes only its result byte.
    if (requestedState in 0..1 && Room.slots.any { it.user?.c === client }) {
        client send AutoReadyResult()
    }
}
