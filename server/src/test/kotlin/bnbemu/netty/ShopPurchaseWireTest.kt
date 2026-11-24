package bnbemu.netty

import bnbemu.packet.*
import bnbemu.server.*
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ShopPurchaseWireTest {
    private fun buy(channel: EmbeddedChannel, product: Int): List<PKT> {
        val request=Unpooled.buffer()
        SendPacketBase(request,3).apply { Encode2(267);Encode4(product);Encode4(0);Encode1(0) }
        channel.writeInbound(request)
        return buildList { while (true) add(channel.readOutbound<PKT>() ?: break) }
    }

    @Test fun actualPurchaseDispatchDoesNotInsertTheNativeCreatedStockTwice() {
        OpcodeManager.recvOps
        // Test real listed bubble/face/decoration/nameplate products, rather
        // than just the standalone account-to-inventory helper.
        for(category in listOf(14,19,10,18)) {
            val p=ShopCatalog.DEFAULT_ITEMS.first { it.category==category && it.tail.price1 in 1..1_000_000 }
            val socket=ClientSocket();val channel=EmbeddedChannel(socket)
            try {
                val user=User(socket,91007,"purchase-wire-${UUID.randomUUID()}")
                socket.users.add(user)
                val initial=user.lucci
                for (quantity in 1..2) {
                    val replies=buy(channel,p.prodId)
                    assertEquals(listOf(202,122),replies.map { OpcodeManager.getSendOp(it::class.java) },
                        "Native S202 applies stock; a following S274 would insert it again")
                    val body=Unpooled.buffer()
                    try {
                        replies.first().encode(SendPacketBase(body,3))
                        assertEquals(0,body.Decode1)
                    } finally { body.release() }
                    val account=LocalShop.store.account(user.accountKey)
                    assertEquals(quantity*p.count,account.items[p.itemId])
                    assertEquals(initial-quantity*p.tail.price1,account.lucci)
                    assertEquals(account.lucci,user.lucci)
                    assertEquals(1,localInventoryEntries(account,0,emptyList()).count { it.itemId==p.itemId })
                }
            } finally {channel.finishAndReleaseAll()}
        }
    }

    @Test fun nationalBattleSuppliesUseDistinctProductsAndPersistTheirPurchasedStock() {
        OpcodeManager.recvOps
        val socket = ClientSocket()
        val channel = EmbeddedChannel(socket)
        try {
            val user = User(socket, 91009, "battle-supplies-${UUID.randomUUID()}")
            socket.users.add(user)
            val initial = LocalShop.store.account(user.accountKey).lucci
            val expected = mapOf(10000 to "Item/ItemNiddle", 10005 to "Item/Spring",
                10001 to "Item/Shield", 75 to "Item/ItemCan", 10010 to "Item/ItemDart",
                10002 to "Item/ItemWildGinseng",
                20243 to "Item/Sensor", 60278 to "Item/RemoteBomb", 10024 to "Item/HollowBomb", 90177 to "Drill", 74 to "Item/ItemSpanner", 76 to "Item/ItemMotor", 77 to "Item/ItemPotion", 78 to "Item/ItemScanner")
            val products = (91_000_001..91_000_014).map { id -> ShopCatalog.DEFAULT_ITEMS.single { it.prodId == id } }
            assertEquals(expected.keys, products.map { it.itemId }.toSet())
            for (p in products) {
                val def = ItemDef.DEFAULT_ITEMS.single { it.itemId == p.itemId }
                assertEquals(expected[p.itemId], def.name)
                assertEquals(1, def.category)
                assertEquals(1, p.tail.f9)
                assertEquals(listOf(202, 122), buy(channel, p.prodId).map { OpcodeManager.getSendOp(it::class.java) })
            }
            val account = LocalShop.store.account(user.accountKey)
            assertEquals(initial - products.sumOf { it.tail.price1 }, account.lucci)
            val inventory = localInventoryEntries(account, 0, emptyList())
            for (p in products) {
                assertEquals(10, account.items[p.itemId])
                assertEquals(10, inventory.single { it.itemId == p.itemId }.param)
            }
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun rejectedPurchaseDoesNotIssueAnyStockOrMoneyUpdate() {
        OpcodeManager.recvOps
        val socket=ClientSocket();val channel=EmbeddedChannel(socket)
        try {
            val user=User(socket,91008,"rejected-purchase-${UUID.randomUUID()}")
            socket.users.add(user)
            val before=LocalShop.store.account(user.accountKey)
            val replies=buy(channel,Int.MAX_VALUE)
            assertEquals(listOf(202),replies.map { OpcodeManager.getSendOp(it::class.java) })
            val body=Unpooled.buffer()
            try {replies.single().encode(SendPacketBase(body,3));assertEquals(1,body.Decode1)} finally {body.release()}
            assertEquals(before,LocalShop.store.account(user.accountKey))
        } finally {channel.finishAndReleaseAll()}
    }
}
