package bnbemu.packet

import bnbemu.netty.PKT

fun LeaveSlot(slot: Int, userId: Int, reason: Int = 0) = PKT {
    Encode1(slot)
    Encode4(userId)
    Encode1(reason)
}
