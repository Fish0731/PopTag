package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.SlotMeta
import bnbemu.server.FLAG_READY
import bnbemu.server.Lobby
import bnbemu.server.Room
import bnbemu.server.state

val ReadyReq = PKTHandler { c ->
    if (readableBytes() < 1) return@PKTHandler
    val ready = Decode1
    val idx = Lobby.slotOf(c)
    if (idx < 0) return@PKTHandler
    val slot = Room.slots[idx]
    val st = slot.state.toInt()
    slot.state = (st and FLAG_READY.inv()) or if (ready != 0) FLAG_READY else 0
    Room.BC(SlotMeta(idx, slot.meta.copyOf()))
}
