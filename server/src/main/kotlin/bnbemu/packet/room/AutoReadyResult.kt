package bnbemu.packet.room

import bnbemu.netty.PKT

// EN S304 reaches 51BA40 in the room stage. Result zero releases the
// room-wide ready wait without changing the active map, seat or menu.
internal fun AutoReadyResult() = PKT { Encode1(0) }
