package bnbemu.packet

import bnbemu.netty.PKT

const val JOIN_NO_ROOM = 1
const val JOIN_REJECTED = 2

fun JoinRoomResult(result: Int, roomNo: Int = 0, key: Int = 0, gameType: Int = 0) = PKT {
    Encode1(result)
    if (result == 0) {
        Encode2(roomNo)
        Encode4(key)
        Encode1(gameType)
        Encode1(0)
        Encode1(0)
    }
}
