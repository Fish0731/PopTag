package bnbemu.packet

import bnbemu.NetConfig
import bnbemu.netty.PKT

fun UnkResult() = PKT {
    Encode1(1) // Res

    Encode1()
    Encode4()
    Encode4(NetConfig.serverIpInt)
    Encode2(3838)
}