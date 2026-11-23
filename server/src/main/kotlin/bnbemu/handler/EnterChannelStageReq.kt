package bnbemu.handler

import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.ChannelsExtraInfo
import bnbemu.packet.ChannelsGameState
import bnbemu.packet.ChannelsInfo
import bnbemu.packet.ChannelsState
import bnbemu.server.Lobby

val EnterChannelStageReq = PKTHandler { c ->
    if (readableBytes() >= 4) Decode4
    Lobby.leaveRoom(c, isDisconnect = false)
    c.send(
        ChannelsInfo(),
        ChannelsState(),
        ChannelsGameState(0),
        ChannelsExtraInfo(),
    )
}
