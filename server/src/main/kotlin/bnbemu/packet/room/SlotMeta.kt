package bnbemu.packet.room

import bnbemu.netty.PKT
import bnbemu.packet.MpPackets

fun SlotMeta(slot: Int, meta: ByteArray) = PKT {
    Encode1(slot)
    EncodeBuffer(MpPackets.ssdEncodeFull(meta))
}
