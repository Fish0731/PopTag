package bnbemu.packet.room

import bnbemu.netty.PKT

// S156: native 54987D removes the obstacle and plays the directional drill animation.
internal fun UseDrillResult(row: Int, column: Int, slot: Int, direction: Int) = PKT {
    Encode1(row)
    Encode1(column)
    Encode1((slot shl 4) or direction)
}
