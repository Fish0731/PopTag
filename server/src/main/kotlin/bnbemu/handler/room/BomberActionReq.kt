package bnbemu.handler.room

import bnbemu.netty.PKTHandler
import bnbemu.server.Lobby

// 局内角色操作/碰撞交互请求 (C→S op 112 / 0x70)
// 委托 Lobby.onBomberAction 进行状态/权限校验，并在跨机联机时广播给同房间其余玩家 (S→C op 146)。
val BomberActionReq = PKTHandler { c ->
    Lobby.onBomberAction(this, c)
}
