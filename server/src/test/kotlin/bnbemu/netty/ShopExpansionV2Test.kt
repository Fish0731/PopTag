package bnbemu.netty

import bnbemu.packet.ItemDef
import bnbemu.packet.ShopCatalog
import bnbemu.packet.ShopPackages
import bnbemu.packet.Unk66
import io.netty.buffer.Unpooled
import bnbemu.server.LocalShopStore
import bnbemu.server.PurchaseStatus
import bnbemu.server.localInventoryEntries
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ShopExpansionV2Test {
    @TempDir lateinit var directory: Path

    // Fixed publication batch: this verifies real catalog wiring and saved ownership,
    // rather than merely checking the input TSV against itself.
    private val names = listOf("都敏俊教授装", "宝宝绿围巾", "燕尾服", "心动时分", "古老的院子", "守望者", "2007年10段账号效果")

    @Test fun nationalCosmeticsArePurchasableWithGoldAndSurviveStoreReloadWithoutDuplicatedEquipment() {
        val products=names.map { name -> requireNotNull(ShopCatalog.DEFAULT_ITEMS.firstOrNull { it.name==name }) { "Missing product $name" } }
        assertEquals(setOf(10,18,19),products.map { it.category }.toSet())
        for((index,p) in products.withIndex()) {
            val user="expansion-$index"
            assertTrue(p.tail.price1 in 120000..680000)
            assertEquals(1,p.tail.f9,"Gold purchase flag")
            val store=LocalShopStore(directory)
            val bought=store.purchase(user,p)
            assertEquals(PurchaseStatus.OK,bought.status)
            assertEquals(3000000-p.tail.price1,bought.account.lucci)
            assertEquals(1,bought.account.items[p.itemId])
            store.changeEquipment(user,0,listOf(100 to p.itemId))
            val persisted=LocalShopStore(directory).account(user)
            assertEquals(p.itemId,persisted.equipment[0]?.get(100))
            assertEquals(1,localInventoryEntries(persisted,0,emptyList()).count { it.itemId==p.itemId })
            assertEquals(1,ShopCatalog.DEFAULT_ITEMS.count { it.itemId==p.itemId },"No duplicate catalog row")
        }
        assertTrue(ItemDef.DEFAULT_ITEMS.any { it.category==19 && it.skinId==6 && it.targetCharId==2 })
        assertTrue(ItemDef.DEFAULT_ITEMS.any { it.category==19 && it.skinId==5 && it.targetCharId==2 })
    }
    @Test fun packageMapLinksRealProductsAndPurchaseCommitsEveryPermanentComponent() {
        val catalog = ShopCatalog.DEFAULT_ITEMS
        val bundle = catalog.single { it.name == "加冕典礼" }
        val items = requireNotNull(ShopPackages.items(bundle.prodId)).toSet()
        assertEquals(setOf(960030214, 960030215, 980030223, 980030224), items)
        val body = Unpooled.buffer()
        try {
            Unk66().encode(SendPacketBase(body, 3))
            val size = body.Decode4
            assertEquals(size, body.readableBytes())
            val decoded = ByteArray(size)
            for (offset in decoded.indices step 4) {
                val value = body.readInt() xor 0x96CA5395.toInt()
                repeat(4) { decoded[offset + it] = (value ushr (it * 8)).toByte() }
            }
            val data = java.nio.ByteBuffer.wrap(decoded).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x18, data.get().toInt())
            assertEquals(size - 5, data.int)
            assertEquals(0, data.get().toInt())
            val rows = List(data.short.toInt()) { data.int to data.int }
            assertFalse(data.hasRemaining())
            val byProduct = catalog.associateBy { it.prodId }
            assertEquals(items, rows.filter { it.first == bundle.prodId }
                .map { byProduct.getValue(it.second).itemId }.toSet())
        } finally { body.release() }

        val store = LocalShopStore(directory)
        val purchase = store.purchase("local-bundle", bundle)
        assertEquals(PurchaseStatus.OK, purchase.status)
        assertEquals(3_000_000 - bundle.tail.price1, purchase.account.lucci)
        val saved = LocalShopStore(directory).account("local-bundle")
        assertEquals(items.associateWith { 1 }, saved.items)
        assertTrue(localInventoryEntries(saved, 0, emptyList()).all { entry -> entry.expireDate.all { it == 0.toByte() } })
        assertEquals(PurchaseStatus.INSUFFICIENT_FUNDS, store.purchase("local-bundle", bundle, 99).status)
        assertEquals(saved, store.account("local-bundle"))
    }

    @Test fun advancedMotionBlurKeepsItsNativeCategoryAndOwnership() {
        val definition = ItemDef.DEFAULT_ITEMS.single { it.itemId == 10023 }
        val product = ShopCatalog.DEFAULT_ITEMS.single { it.itemId == definition.itemId }
        assertEquals("Item/AdvMotionBlur", definition.name)
        assertEquals(20, definition.category)
        assertEquals(20, product.category)
        assertEquals(definition.desc, product.name)
        assertEquals(320_000, product.tail.price1)
        assertEquals(1, product.tail.f9)
        val store = LocalShopStore(directory)
        val purchase = store.purchase("advanced-motion-blur", product)
        assertEquals(PurchaseStatus.OK, purchase.status)
        assertEquals(2_680_000, purchase.account.lucci)
        val restored = LocalShopStore(directory).account("advanced-motion-blur")
        val entry = localInventoryEntries(restored, 0, emptyList()).single()
        assertEquals(definition.itemId, entry.itemId)
        assertEquals(definition.name, entry.token)
    }

}
