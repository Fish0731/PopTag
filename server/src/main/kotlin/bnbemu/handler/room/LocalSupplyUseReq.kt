package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// PopTag local extension. Emitted after the original inventory decrement by the paired client.
val LocalSupplyUseReq = PKTHandler { client ->
    if (readableBytes() != 7) return@PKTHandler
    val inventory = Decode1
    val itemId = Decode4
    val previousQuantity = Decode2
    Room.recordLocalSupplyUse(client, inventory, itemId, previousQuantity)
}
