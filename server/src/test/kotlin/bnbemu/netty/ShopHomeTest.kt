package bnbemu.netty

import bnbemu.packet.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShopHomeTest {
    @Test fun nativeHomeSelectorsReceiveSixNewAndFourRecommendedRealProducts() {
        val catalog = ShopCatalog.DEFAULT_ITEMS
        val encoded = ShopCatalog.decodeBlock(ShopCatalog.buildBlock(catalog))
        assertEquals(catalog, encoded)
        val fresh = encoded.filter { it.tail.f7.toInt() in -9999..-1001 }
        val recommended = encoded.filter { it.tail.f7.toInt() <= -10000 }
        assertEquals(38, fresh.size)
        assertEquals(7, recommended.size)
        assertEquals(45, (fresh + recommended).map { it.prodId }.distinct().size)
        val definitions = ItemDef.DEFAULT_ITEMS.associateBy { it.itemId }
        val featured = fresh.sortedBy { it.tail.f7.toInt() }
        assertEquals(listOf(
            41, 14, 9, 27, 32, 28, 15, 16, 12, 38, 19, 17, 11, 10, 8, 7, 5, 4,
            23, 22, 42, 43, 24, 35, 3, 18, 34, 29, 13, 30, 61, 45,
        ),
            featured.take(32).map { definitions.getValue(it.itemId).param })
        assertTrue(featured.take(32).all { it.category == 14 })
        assertEquals(listOf(10023, 900800002, 30273, 900800004, 900800005, 90118),
            featured.drop(32).map { it.itemId })
        val ordered = recommended.sortedBy { it.tail.f7.toInt() }
        assertEquals(listOf(5021,5022,5025,5026), ordered.take(4).map { definitions.getValue(it.itemId).skinId })
        assertEquals(listOf(5023,5024,5027), ordered.drop(4).map { definitions.getValue(it.itemId).skinId })
        for (product in fresh + recommended) {
            assertTrue(product.tail.price1 > 0)
            assertNotNull(ItemDef.DEFAULT_ITEMS.find { it.itemId == product.itemId })
        }
    }

    @Test fun reportedDuplicatesAndMisidentifiedUnlockAreNotForSaleButDefinitionsSurvive() {
        assertFalse(ShopCatalog.DEFAULT_ITEMS.any { it.itemId == 2082 })
        val unlock = ItemDef.DEFAULT_ITEMS.single { it.itemId == 2082 }
        assertEquals(16, unlock.category)
        assertEquals("蓝妹妹使用权", unlock.desc)
        for (style in listOf(121,126)) {
            val ids = ItemDef.DEFAULT_ITEMS.filter { it.category == 14 && it.param == style }.map { it.itemId }
            assertTrue(ids.isNotEmpty())
            assertFalse(ShopCatalog.DEFAULT_ITEMS.any { it.itemId in ids })
        }
        for (style in listOf(119,125)) assertTrue(ShopCatalog.DEFAULT_ITEMS.any { product ->
            ItemDef.DEFAULT_ITEMS.any { it.itemId == product.itemId && it.category == 14 && it.param == style }
        })
    }
}
