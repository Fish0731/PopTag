package bnbemu.packet

/** CN324 package membership, linked to the matching local cosmetic item IDs. */
internal object ShopPackages {
    private const val PRODUCT_BASE = 92_000_000

    private data class Package(val sourceId: Int, val category: Int, val name: String, val items: List<Int>) {
        val productId get() = PRODUCT_BASE + sourceId
    }

    private val packages = listOf(
        Package(4014, 22, "金玉满堂", listOf(960000649, 960000650, 960000651)),
        Package(4095, 22, "枫叶的故事", listOf(960000769, 960000773, 770038)),
        Package(7002, 21, "呼呼婴儿装", listOf(980060105, 980060106)),
        Package(31452, 21, "新郎礼服", listOf(970020101, 970020107)),
        Package(50136, 21, "中国武士", listOf(970020247, 980020259, 980020260)),
        Package(111505, 23, "加冕典礼", listOf(960030214, 960030215, 980030223, 980030224)),
        Package(111691, 23, "懒虫呼呼套装", listOf(960060102, 960060103, 960060104, 980060105, 980060106)),
        Package(111806, 21, "加勒比海盗", listOf(970060197, 980060198, 980060199)),
        Package(2000004, 22, "老虎背景套装", listOf(960088217, 960088218, 770075)),
        Package(111960, 22, "帝企鹅礼包", listOf(960060315, 960060316, 770065)),
        Package(4021, 22, "真情玫瑰", listOf(740096, 770018)),
        Package(5489, 21, "金童", listOf(970030162, 980030163, 970030164)),
        Package(5499, 22, "冬天里的一把火", listOf(960000695, 960000696, 760058, 960000699)),
        Package(5523, 21, "宝宝冬装", listOf(980030193, 980030194)),
        Package(7001, 22, "乖宝贝系列背景", listOf(960060102, 960060103, 960060104)),
        Package(8155, 21, "星巴巴大礼包", listOf(960151182, 960151183, 960152217)),
        Package(31381, 21, "寒冷清秋", listOf(970020117, 970020132, 980020134)),
        Package(31384, 22, "秋天的故事", listOf(960020081, 960020082, 960020083)),
        Package(31386, 22, "温暖初冬", listOf(960000837, 960000839)),
        Package(31387, 23, "温暖初冬超值礼包", listOf(960000837, 960000839, 900000069)),
        Package(31397, 21, "宝宝童子军", listOf(970020100, 970020103)),
        Package(31453, 21, "新娘婚纱", listOf(2067, 940000798)),
        Package(31464, 22, "滂沱大雨", listOf(960000724, 960000726, 960000728)),
        Package(40043, 21, "榴莲头套装", listOf(970020177, 980020178)),
        Package(50005, 22, "无尽爱恋", listOf(960001037, 960001038, 760083)),
        Package(50133, 21, "嬉哈街头装", listOf(970020245, 970020246)),
        Package(50138, 22, "迷彩大兵", listOf(960020261, 960020262)),
        Package(50148, 22, "我的星梦奇缘", listOf(960000339, 960000342)),
        Package(111284, 21, "黑马王子", listOf(970020122, 970020123, 970020124, 970020125)),
        Package(111520, 21, "小王子", listOf(980030223, 980030224)),
        Package(111663, 21, "武士精神", listOf(980030326, 970030327)),
        Package(111667, 22, "泼水礼包", listOf(960030328, 960030329, 960030330)),
        Package(111791, 21, "动感超人", listOf(980060187, 980060188)),
        Package(111885, 22, "旷世情缘", listOf(960060265, 960060266)),
        Package(111900, 22, "花妖小筑套装", listOf(960060272, 960060273)),
        Package(111901, 21, "恐龙美眉装", listOf(980060276, 980060277)),
        Package(111987, 21, "牛仔很忙", listOf(980088020, 970088021)),
        Package(111988, 22, "月亮兔礼包", listOf(960088015, 960088016, 760097, 960088018)),
        Package(112027, 21, "小乖牛仔装", listOf(980088044, 980088045)),
        Package(112041, 21, "舞动灵感套装", listOf(980088063, 980088064)),
        Package(112042, 21, "金刚罗密欧", listOf(980088065, 980088066)),
        Package(112090, 22, "海皇套装", listOf(770067, 960088099, 960088100, 760098)),
        Package(112116, 21, "小乖水手套装", listOf(980088125, 980088126)),
        Package(112338, 21, "拷贝超人", listOf(985088306, 985088307)),
        Package(112365, 22, "向日葵套装", listOf(960088325, 960088326, 960088327)),
        Package(1500878, 22, "海底世界套装", listOf(960150669, 960150670, 960150671, 960150672)),
        Package(1500911, 22, "我爱切糕", listOf(960150704, 960150705)),
        Package(1501705, 21, "宝宝警察蜀黍套装", listOf(970151446, 985151447, 985151448)),
        // Local themed bundles combining existing character and room cosmetics.
        Package(2000001, 23, "小乖圣诞全套", listOf(980030229, 980030230, 960000322, 960000316, 960152440)),
        Package(2000002, 23, "宝宝加勒比海盗全套", listOf(970060197, 980060198, 980060199, 960000277, 960150596)),
        Package(2000003, 23, "宝宝圣诞全套", listOf(970030174, 970030175, 970020175, 960000861, 960000862, 960060319)),
        Package(2000005, 22, "雪倾城背景套装", listOf(960150244, 960150245, 960150246)),
        Package(2000006, 22, "幽幽雪背景套装", listOf(960088031, 960088032, 770069)),
        Package(2000007, 22, "情侣星星背景套装", listOf(960030279, 960030281, 770025)),
        Package(2000008, 22, "图腾背景套装", listOf(960030232, 960030233)),
        Package(2000009, 22, "暗夜的妖媚背景套装", listOf(960088194, 960088195)),
        Package(2000010, 22, "缤纷世界背景套装", listOf(960088214, 740272)),
        Package(2000011, 22, "子爵套装", listOf(960030151, 960030152, 960030153, 770055)),
        Package(2000012, 23, "紫焰流觞套装", listOf(930000045, 960150588, 960150589)),
    )
    private val byId = packages.associateBy { it.productId }

    fun items(productId: Int): List<Int>? = byId[productId]?.items

    fun products(singles: List<ShopItemEntry>): List<ShopItemEntry> {
        val byItem = singles.associateBy { it.itemId }
        return packages.map { pack ->
            val subtotal = pack.items.sumOf { byItem.getValue(it).tail.price1 }
            val price = (subtotal * 8 / 10 / 10_000 * 10_000).coerceIn(10_000, 880_000)
            ShopItemEntry(pack.productId, pack.category, pack.name, itemId = 1,
                desc = "Icon/Set_defaulticon", tail = ShopItemTail(price1 = price, price2 = price, f9 = 1))
        }
    }

    fun relationships(catalog: List<ShopItemEntry>): List<Pair<Int, Int>> {
        val singles = catalog.filter { it.prodId !in byId }.associateBy { it.itemId }
        return packages.flatMap { pack -> pack.items.map { pack.productId to singles.getValue(it).prodId } }
    }
}
