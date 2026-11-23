package bnbemu.packet

import bnbemu.NetConfig
import bnbemu.isEN
import bnbemu.netty.PKT

// server = CMultiServer::Init
fun MultiServerInit() = PKT {
    if (isEN) {
        val plain = EN_MULTISVR_TEMPLATE.clone()
        val ip = NetConfig.serverIpInt
        plain[38] = (ip ushr 24).toByte()
        plain[39] = (ip ushr 16).toByte()
        plain[40] = (ip ushr 8).toByte()
        plain[41] = ip.toByte()
        Encode1Bool(true)
        Encode4(46)
        EncodeBuffer(ssdEncode(plain))
        Encode2(0)
        Encode1(0)
    } else {
        Encode1Bool()
        // TODO
        Encode1Bool()
        // TODO
    }
}

private val EN_MULTISVR_TEMPLATE = byteArrayOf(
    0x18, 0x29, 0x00, 0x00, 0x00, 0x00, 0x01, 0x06,
    0x00, 0x00, 0x00, 0x50, 0x6F, 0x70, 0x54, 0x61,
    0x67, 0x0B, 0x00, 0x00, 0x00, 0x4D, 0x61, 0x69,
    0x6E, 0x20, 0x53, 0x65, 0x72, 0x76, 0x65, 0x72,
    0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x7F, 0x00,
    0x00, 0x01, 0xFE.toByte(), 0x0E
)
