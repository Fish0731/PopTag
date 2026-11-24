package bnbemu.packet.room

import bnbemu.netty.PKT

// S158 / 54C285 activates a native ghost object from the board's eight-ghost pool.
internal fun SpawnMapGhost(ghostIndex: Int, playerSlot: Int, column: Int, row: Int) = PKT {
    Encode1(ghostIndex)
    Encode1(0x80 or playerSlot)
    Encode1(row)
    Encode1(column)
}
