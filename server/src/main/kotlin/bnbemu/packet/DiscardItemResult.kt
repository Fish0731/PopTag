package bnbemu.packet

import bnbemu.netty.PKT

fun DiscardItemResult(success: Boolean, itemId: Int = 0, displayIndex: Int = 0) = PKT {
    Encode1(if (success) 2 else 0)
    if (success) {
        Encode4(itemId)
        Encode4(displayIndex)
    }
}
