package bnbemu.handler

import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.DiscardItemResult
import bnbemu.packet.SessionList
import bnbemu.packet.UserMapInfo
import bnbemu.packet.localUserMapEntry
import bnbemu.server.LocalPlayers
import bnbemu.server.LocalShop
import bnbemu.server.Room

// The native inventory dialog sends the selected item ID and its display index.
val DiscardItemReq = PKTHandler { c ->
    if (readableBytes() !in 8..512) {
        c send DiscardItemResult(false)
        return@PKTHandler
    }
    val itemId = Decode4
    // Some dialog variants include localized item text between the two fields.
    skipBytes(readableBytes() - 4)
    val displayIndex = Decode4
    val owner = c.users.singleOrNull() ?: c.ownProfilePlayerIndex?.let { c.users.getOrNull(it) }
    try {
        val deleted = owner != null && LocalShop.store.discard(owner.accountKey, itemId)
        c send DiscardItemResult(deleted, itemId, displayIndex)
        if (deleted && owner != null) {
            c.send(UserMapInfo(users = c.users.map(::localUserMapEntry)), SessionList(LocalPlayers.sessionEntries(c)))
            Room.refreshWaitingAppearance(owner)
        }
    } catch (e: java.io.IOException) {
        c send DiscardItemResult(false)
    }
}
