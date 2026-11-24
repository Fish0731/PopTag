package bnbemu.data

/** Display names only: resource paths, product IDs and map ordering stay unchanged. */
object LocalChineseNames {
    private val officialItemNames: Map<Int, String> by lazy {
        requireNotNull(javaClass.getResourceAsStream("/localization/zh-CN-official-item-names.tsv"))
            .bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { '\t' in it }.associate {
                    it.substringBefore('\t').toInt() to it.substringAfter('\t')
                }
            }
    }
    private val names: Map<String, String> by lazy {
        listOf("zh-CN-item-names.tsv", "zh-CN-shop-names.tsv").flatMap { file ->
            requireNotNull(javaClass.getResourceAsStream("/localization/$file"))
                .bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.filter { '\t' in it }.map {
                        it.substringBefore('\t') to it.substringAfter('\t').replace("\\n", "\n")
                    }.toList()
                }
        }.toMap()
    }

    fun item(original: String, resource: String, category: Int, itemId: Int): String {
        // This local id is repurposed as the character unlock, whereas CN324's
        // same numeric id identifies an unrelated butterfly decoration.
        if (itemId == 2082 && category == 16) return "蓝妹妹使用权"
        officialItemNames[itemId]?.let { return it }
        names[original]?.let { return it }
        Regex("(?:角色装饰 |CharDeco(?:Adj)? )([A-G])(\\d+)").matchEntire(original)?.let {
            val part = mapOf("A" to "头饰", "B" to "面饰", "C" to "服装", "D" to "背饰", "E" to "翅膀", "F" to "角色饰品", "G" to "角色特效").getValue(it.groupValues[1])
            return "$part ${it.groupValues[1]}${it.groupValues[2]}"
        }
        if (original.any { it in '\u3400'..'\u9fff' }) return original
        val patterns = listOf(
            "Bomb " to "水泡 ", "Bomb" to "水泡 ", "CharDecoAdj " to "角色装饰 ",
            "CharDeco " to "角色装饰 ", "Slot background " to "背景 ",
            "Slot flag " to "旗帜 ", "Slot prop " to "摆件 ", "Slot border " to "边框 ",
            "LadderDeco " to "段位装饰 ", "IDDeco " to "名牌 ", "Fanfare " to "入场特效 "
        )
        patterns.firstOrNull { original.startsWith(it.first) && original.removePrefix(it.first).any(Char::isDigit) }
            ?.let { return it.second + original.removePrefix(it.first) }
        val label = when {
            resource.startsWith("BombDeco/") -> "水泡"
            resource.startsWith("CharDeco/") -> "角色装饰"
            resource.startsWith("IDDeco/") -> "名牌"
            category == 10 -> "背景装饰"
            category == 12 -> "角色装饰"
            category == 14 -> "水泡"
            category == 16 -> "角色卡"
            category == 18 -> "名牌"
            category == 29 -> "段位装饰"
            else -> "道具"
        }
        val code = resource.substringAfterLast('/').filter { it.isDigit() }.ifEmpty { itemId.toString() }
        return "$label $code"
    }

    private val mapGroups = mapOf(
        "Bonus" to "奖励地图", "Bula" to "宝藏", "Camp" to "坦克",
        "Cemetery" to "墓地", "Cookie" to "饼干城", "Desert" to "沙漠",
        "Factory" to "工厂", "Forest" to "森林", "Ice" to "冰川",
        "MonRobot" to "机械帝国", "MonSeal" to "海皇", "MonTurtle" to "神秘海域", "MonPen" to "帝企鹅", "Monster" to "章鱼岛", "Ocean" to "大海",
        "PangLand" to "金银岛", "Patrit" to "海盗", "Rabbit" to "猎场",
        "Random" to "随机地图", "Tomb" to "古墓", "Village" to "小区",
        "Wriggle" to "怪物争霸", "RPG_DesertOcto" to "冒险·沙漠章鱼",
        "RPG_Event" to "冒险·活动", "RPG_ForestGolem" to "冒险·森林巨人",
        "RPG_ForestSeal" to "冒险·森林海豹", "RPG_IceMonPen" to "冒险·冰雪企鹅",
        "RPG_MineGolem" to "冒险·矿山巨人", "RPG_PatritOcto" to "冒险·海盗章鱼",
        "RPG_TombSeal" to "冒险·古墓海豹",
        // 2026-11-08 扩展地图分组（私服 cav6 全量地图表 667 条合入）
        "space" to "太空", "Aqua" to "水族馆", "BlackLucy" to "黑露西",
        "Clover" to "三叶草", "DarkCastle" to "黑暗城堡", "Hamster" to "仓鼠农场",
        "LovelyRoom" to "温馨小屋", "MonCity" to "怪物都市", "Popcade" to "泡泡街机",
        "Zombie" to "僵尸危机", "SandboxHidden" to "沙盒隐藏",
        "GM1_MonGolem" to "GM·石巨人", "S01_MonSeal" to "海豹·一",
        "S02_MonSeal" to "海豹·二", "T01_MonTurtle" to "海龟·一",
        "T02_MonTurtle" to "海龟·二", "R01_MonRobot" to "机器人·一",
        "R02_MonRobot" to "机器人·二", "F01_Mine" to "矿山·一",
        "F01_Tomb" to "古墓·一", "K01_Kart" to "卡丁车", "K01_KFG" to "卡丁车FG",
        "Isactoast" to "吐司小岛", "ExBula" to "特别·布拉村", "ExCamp" to "特别·营地",
        "ExFactory" to "特别·工厂", "ExForest" to "特别·森林", "ExPatrit" to "特别·海盗船",
        "ExRandom" to "特别·随机", "ExTomb" to "特别·古墓", "ExVillage" to "特别·城镇"
    )

    private val officialMapNames: Map<String, String> by lazy {
        checkNotNull(javaClass.getResourceAsStream("/localization/zh-CN-map-names.tsv"))
            .bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { '\t' in it && !it.startsWith("#") }.associate {
                    it.substringBefore('\t') to it.substringAfter('\t')
                }
            }
    }

    fun map(record: FxdynRecord): String {
        // The national map table itself names this campaign in English.
        if (record.group == "MonUnlimited") return record.display
        officialMapNames[record.name]?.let { return it }
        if (record.name == "HotRandom") return "热门随机"
        val group = mapGroups[record.group] ?: error("Untranslated map group ${record.group}")
        if (record.display.contains("Random", true) || (record.subtype != 0 && record.stage == "none")) return "$group·随机"
        val suffix = Regex("\\d+").findAll(record.display).joinToString("-") { it.value }
        return if (suffix.isEmpty()) group else "$group $suffix"
    }
}
