package bnbemu.packet

/** CShopStageNew::RefreshMain (5CA4C8) fills six new and four recommended slots.
 * Native CShopItem predicates 660E66/660E7E read signed tail.f7:
 * recommended <= -10000; new strictly between -10000 and -1000.
 */
internal fun refreshShopHome(items: List<ShopItemEntry>): List<ShopItemEntry> {
    val definitions = ItemDef.DEFAULT_ITEMS.associateBy { it.itemId }
    fun roomDecoration(part: Int, style: Int) = items.firstOrNull { item ->
        definitions[item.itemId]?.let {
            it.category==10 && it.targetCharId==part && it.skinId==style
        } == true
    }
    // Classic bubbles: 巧克力、七彩、绿色西瓜、奶牛、绿光妖精、鬼眼.
    // The first six cards also fill the top row of the native shop home.
    val bubbleStyles = listOf(
        41, 14, 9, 27, 32, 28, 15, 16, 12, 38, 19, 17, 11, 10, 8, 7, 5, 4,
        23, 22, 42, 43, 24, 35, 3, 18, 34, 29, 13, 30, 61, 45,
    )
    val bubbles = bubbleStyles.map { style ->
        checkNotNull(items.firstOrNull { item ->
            definitions[item.itemId]?.let { it.category==14 && it.param==style } == true
        }) { "Missing home classic bubble $style" }
    }
    val classicTrails = listOf(10023, 900800002, 30273, 900800004, 900800005, 90118).map { itemId ->
        items.single { it.itemId == itemId }
    }
    val featured = bubbles + classicTrails
    val recommended = listOf(5021,5022,5025,5026).mapNotNull { roomDecoration(1,it) }
    // Additional recommendation-list products follow the four home cards.
    // These original regional backgrounds have no matching flag/border bundle.
    val regionalRecommendations = listOf(5023,5024,5027).map { style ->
        checkNotNull(roomDecoration(1,style)) { "Missing regional background $style" }
    }
    check(recommended.size == 4) { "Home backgrounds must resolve to purchasable products" }
    val rank = (featured.mapIndexed { index, item -> item.prodId to -2000L + index } +
        (recommended + regionalRecommendations).mapIndexed { i,item -> item.prodId to -11000L+i }).toMap()
    return items.map { item ->
        val value = rank[item.prodId] ?: if (item.tail.f7.toInt() < -1000) 0L else item.tail.f7
        item.copy(tail = item.tail.copy(f7 = value and 0xFFFFFFFFL))
    }
}
