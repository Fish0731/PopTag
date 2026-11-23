package bnbemu.packet

import bnbemu.netty.PKT

fun WhisperShow(line: ByteArray) = PKT {
    Encode2(line.size)
    EncodeBuffer(MpPackets.ssdEncodeFull(line))
}
