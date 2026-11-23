package bnbemu.packet

import bnbemu.netty.PKT

fun ChatShow(line: ByteArray, type: Int = 0) = PKT {
    Encode2(line.size)
    EncodeBuffer(MpPackets.ssdEncodeFull(line))
    Encode1(type)
}
