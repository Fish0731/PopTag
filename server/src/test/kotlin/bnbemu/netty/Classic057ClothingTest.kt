package bnbemu.netty

import bnbemu.packet.*
import bnbemu.server.*
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class Classic057ClothingTest {
    @TempDir lateinit var directory: Path
    @Test fun allClassicClothesAndHatsHavePermanentPurchasableGoldProducts() {
        val definitions=ItemDef.DEFAULT_ITEMS.filter { it.itemId in 980_000_000 until 985_000_000 }
        assertEquals(126,definitions.size)
        assertEquals(mapOf(5 to 63,6 to 63),definitions.groupingBy { it.skinId }.eachCount())
        assertEquals(mapOf(10 to 56,11 to 70),definitions.groupingBy { it.targetCharId }.eachCount())
        for(item in definitions) {
            assertEquals(19,item.category)
            val product=ShopCatalog.DEFAULT_ITEMS.single { it.itemId==item.itemId }
            assertEquals(1,product.tail.f9)
            assertEquals(if(item.targetCharId==10) 220000 else 180000,product.tail.price1)
            assertEquals(1,product.count)
        }
        assertTrue(definitions.any { it.desc=="小乖将军服" })
        assertTrue(definitions.any { it.desc=="小乖将军帽" })
    }
    @Test fun roomAvatarWireCarriesClassicClothingAndHatSlotsWithoutRenumbering() {
        val cloth=ItemDef.DEFAULT_ITEMS.first { it.itemId>=980_000_000 && it.skinId==6 && it.targetCharId==10 }
        val hat=ItemDef.DEFAULT_ITEMS.first { it.itemId>=980_000_000 && it.skinId==6 && it.targetCharId==11 }
        val unsupported=cloth.copy(itemId=1,targetCharId=12)
        val body=Unpooled.buffer()
        try {
            SendPacketBase(body,3).encodeAvatarDefinitions(listOf(cloth,hat,unsupported))
            assertEquals(2,body.Decode1)
            for(item in listOf(cloth,hat)) {
                assertEquals(6,body.Decode4);assertEquals(item.targetCharId,body.Decode4);assertEquals(item.param,body.Decode4)
            }
            assertEquals(0,body.readableBytes())
        } finally {body.release()}
        val store=LocalShopStore(directory)
        for(item in listOf(cloth,hat)) {
            assertEquals(PurchaseStatus.OK,store.purchase("classic",ShopCatalog.DEFAULT_ITEMS.single { it.itemId==item.itemId }).status)
            store.changeEquipment("classic",0,listOf((if(item.targetCharId==10) 122 else 123) to item.itemId))
        }
        val loaded=LocalShopStore(directory).account("classic")
        for(item in listOf(cloth,hat)) assertEquals(1,localInventoryEntries(loaded,0,emptyList()).count { it.itemId==item.itemId })
    }
    @Test fun homeSlotsSellTheShownBackgroundsAndClassicBubbles() {
        val home=ShopCatalog.DEFAULT_ITEMS.filter { it.tail.f7.toInt() < -1000 }
        assertEquals(45,home.size)
        val definitions=ItemDef.DEFAULT_ITEMS.associateBy {it.itemId}
        val recommended=home.filter {it.tail.f7.toInt()<= -10000}
        assertEquals(setOf(5021,5022,5025,5026,5023,5024,5027),recommended.map { definitions.getValue(it.itemId).skinId }.toSet())
        assertTrue(recommended.all {definitions.getValue(it.itemId).targetCharId==1})
        val bubbles=(home-recommended.toSet()).sortedBy {it.tail.f7.toInt()}.take(6)
        assertEquals(listOf(41,14,9,27,32,28),bubbles.map {definitions.getValue(it.itemId).param})
        assertTrue(bubbles.all {definitions.getValue(it.itemId).category==14})
    }
}
