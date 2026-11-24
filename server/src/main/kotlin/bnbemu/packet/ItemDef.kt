package bnbemu.packet

import bnbemu.ACP
import bnbemu.netty.PKT
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 物品定义条目 (与 CA.exe CUserItem / UserItem 0x00676CFA 一一对应)
 *
 * 逆向确认 (CA.exe, 2026-11-01):
 * 1. 客户端物品定义加载与存储 (CUserItemMan::Read / CItemDef::Read):
 *    - 客户端处理入口: CSelectServerStage::OnPacket (0x00502DC8 -> 0x004F81E7 -> 0x0067877F)
 *    - 全局物品管理器单例: 0x00993D58 (CUserItemMan)
 *    - 封包结构: [Encode4(blob.size)][SSD 加密资料块]
 *    - SSD 块明文结构:
 *      - 块头 (8字节): [0x18][blockLen:2 LE][0x00, 0x00, 0x00][itemCount:2 LE]
 *      - N × ItemDefEntry
 *      - 尾部不足 4 字节按 SSD 标准规则混淆: 2B LE 读 ^ 0xA569 按 BE 写回, 1B ^ 0x5A
 *
 * 2. 字段逆向布局 (0x00676CFA CItemDef::Read):
 * @param itemId 物品唯一定义 ID (u32 LE, [esi + 8])
 * @param category 物品分类 (u8, [esi + 0x14], 3=表情/动作, 10=装扮背景, 12=角色装扮/特效, 13=背包, 14=水泡, 15=消耗品, 16=进阶角色卡, 18=旗帜/标牌, 29=气球背景, 31=情侣戒指, 36=进阶卡槽位)
 * @param name 内部标识/模型资源路径 (u32 LE 长度 + ASCII 字符串, [esi + 0x20], 如 "Dao", "Item/CharDeco", "Item/Bomb")
 * @param skinId 进阶角色皮肤 ID / 背景序号 (u32 LE, [esi + 0x38], 如蓝妹妹为 10, 未指定时为 -1 或 0)
 * @param targetCharId 目标基础角色 ID (u32 LE, [esi + 0x44], 如 4 对应 Marid/蓝蓝, 0 为通用/全角色/Bazzi)
 * @param param 附加参数/槽位序号/水泡序号 (u32 LE, [esi + 0x50])
 * @param desc 物品描述/显示名称 (u32 LE 长度 + ASCII 字符串, [esi + 0x5C], 如 "CharDeco E0001", "Bomb 001")
 * @param flags 属性标志/状态 (u16 LE, [esi + 0x74], 通常为 0 或 1)
 * @param status 启用/生效状态 (u8, [esi + 0x8C], 通常为 1 或 0)
 */
data class ItemDefEntry(
    val itemId: Int,
    val category: Int,
    val name: String,
    val skinId: Int = 0,
    val targetCharId: Int = 0,
    val param: Int = 0,
    val desc: String = "",
    val flags: Int = 0,
    val status: Int = 1,
) {
    fun encode(out: ByteArrayOutputStream) {
        out.u32le(itemId)
        out.u8(category)
        val nameBytes = name.toByteArray(ACP)
        out.u32le(nameBytes.size)
        out.write(nameBytes)
        out.u32le(skinId)
        out.u32le(targetCharId)
        out.u32le(param)
        val descBytes = desc.toByteArray(ACP)
        out.u32le(descBytes.size)
        out.write(descBytes)
        out.u16le(flags)
        out.u8(status)
    }

    companion object {
        fun decode(buf: ByteBuffer): ItemDefEntry {
            val itemId = buf.int
            val category = buf.get().toInt() and 0xFF
            val nameLen = buf.int
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            val name = String(nameBytes, ACP)
            val skinId = buf.int
            val targetCharId = buf.int
            val param = buf.int
            val descLen = buf.int
            val descBytes = ByteArray(descLen)
            buf.get(descBytes)
            val desc = String(descBytes, ACP)
            val flags = buf.short.toInt() and 0xFFFF
            val status = buf.get().toInt() and 0xFF
            return ItemDefEntry(itemId, category, name, skinId, targetCharId, param, desc, flags, status)
        }
    }
}

/**
 * 物品定义目录管理器 (Opcode 60, S→C)
 */
object ItemDef {
    /**
     * 从基础数据文件加载历史 882 条基础物品定义
     */
    fun loadBaseItems(): List<ItemDefEntry> {
        val stream = ItemDef::class.java.getResourceAsStream("/data/item_def_base.bin")
            ?: File("src/main/resources/data/item_def_base.bin").takeIf { it.exists() }?.inputStream()
        if (stream == null) {
            throw RuntimeException("Failed to load item_def_base.bin")
        }
        val raw = stream.use { it.readAllBytes() }
        return decodeBlock(raw)
    }

    /**
     * 将明文 SSD 数据块解析为 ItemDefEntry 列表
     */
    fun decodeBlock(bytes: ByteArray): List<ItemDefEntry> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.get().toInt() and 0xFF
        require(magic == 0x18) { "Invalid SSD magic: 0x${magic.toString(16)}" }
        // Same 24-bit SSD envelope used by ShopCatalog and native 78F1E0.
        val blockLen = (buf.short.toInt() and 0xFFFF) or ((buf.get().toInt() and 0xFF) shl 16)
        require(blockLen == bytes.size - 5) { "Invalid item definition SSD length" }
        buf.get(); buf.get()
        val count = buf.short.toInt() and 0xFFFF
        val items = ArrayList<ItemDefEntry>(count)
        for (i in 0 until count) {
            items.add(ItemDefEntry.decode(buf))
        }
        return items
    }

    /**
     * 将条目列表序列化组装为明文 SSD 数据块
     */
    fun buildBlock(items: List<ItemDefEntry>): ByteArray {
        require(items.size <= 65535)
        val out = ByteArrayOutputStream()
        out.u8(0x18)
        out.u16le(0) // blockLen 占位 (组装完成后回填)
        out.u8(0); out.u8(0); out.u8(0) // 3 字节保留位
        out.u16le(items.size)
        for (item in items) {
            item.encode(out)
        }
        val ba = out.toByteArray()
        val blockLen = ba.size - 5
        require(blockLen <= 0xFFFFFF)
        ba[1] = (blockLen and 0xFF).toByte()
        ba[2] = ((blockLen shr 8) and 0xFF).toByte()
        ba[3] = ((blockLen shr 16) and 0xFF).toByte()
        return ba
    }

    /**
     * 组装条目列表并加密为 SSD 格式
     * (每 DWORD LE 读取 ^ 0x96CA5395 按 BE 写回, 尾部不足 4B 按 LE 读 ^ 0xA569 按 BE 写回 / ^ 0x5A)
     */
    fun encode(items: List<ItemDefEntry>): ByteArray {
        val plain = compressLargeSsd(buildBlock(items))
        val out = ByteArray(plain.size)
        var i = 0
        while (i + 4 <= plain.size) {
            val le = (plain[i].toInt() and 0xFF) or
                    ((plain[i + 1].toInt() and 0xFF) shl 8) or
                    ((plain[i + 2].toInt() and 0xFF) shl 16) or
                    ((plain[i + 3].toInt() and 0xFF) shl 24)
            val enc = le xor 0x96CA5395.toInt()
            out[i] = (enc ushr 24).toByte()
            out[i + 1] = (enc ushr 16).toByte()
            out[i + 2] = (enc ushr 8).toByte()
            out[i + 3] = enc.toByte()
            i += 4
        }
        var rem = plain.size - i
        if (rem >= 2) {
            val w = (plain[i].toInt() and 0xFF) or ((plain[i + 1].toInt() and 0xFF) shl 8)
            val encW = (w xor 0xA569) and 0xFFFF
            out[i] = (encW ushr 8).toByte()
            out[i + 1] = encW.toByte()
            i += 2
            rem -= 2
        }
        if (rem == 1) {
            out[i] = (plain[i].toInt() xor 0x5A).toByte()
        }
        return out
    }

    /**
     * 默认下发物品定义目录：基础 882 条物品 + 蓝妹妹 (Posh Evie / LuxuryMarid, ID 10) 角色进阶卡定义
     */
    val DEFAULT_ITEMS: List<ItemDefEntry> by lazy {
        val list = loadBaseItems().toMutableList()
        requireNotNull(ItemDef::class.java.getResourceAsStream("/data/local_extra_items.tsv"))
            .bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val f = line.split('\t')
                    require(f.size == 9)
                    val item = ItemDefEntry(f[0].toInt(), f[1].toInt(), f[2], f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6], f[7].toInt(), f[8].toInt())
                    require(list.none { it.itemId == item.itemId })
                    list.add(item)
                }
            }
        // Full basic-character cards are required by inventory/shop previews.
        // The captured ItemDef table contained only Dao (id 1).
        listOf("Dao", "Dizini", "Mos", "Ethi", "Marid", "Bazzi", "Uni", "Kephi")
            .forEachIndexed { character, resource ->
                if (list.none { it.itemId == character + 1 }) {
                    list.add(ItemDefEntry(character + 1, 15, resource,
                        targetCharId = character, param = character,
                        desc = listOf("皮蛋", "黑妞", "小强", "葱头", "蓝蓝", "宝宝", "小乖", "胖墩")[character]))
                }
            }
        // The captured catalog omitted battle consumables. NPC provisioning at
        // 0x599D3F looks these up by resource name, and otherwise grants nothing.
        // CPlayer hotbar (0x473323 -> 0x671FC2) reads inventory type 8,
        // and accepts consumables category 1. Category 42 is a package, not a needle.
        list.add(ItemDefEntry(10000, 1, "Item/ItemNiddle", skinId = 999999, desc = "自救针"))
        list.add(ItemDefEntry(10001, 1, "Item/Shield", desc = "盾牌"))
        list.add(ItemDefEntry(10002, 1, "Item/ItemWildGinseng", desc = "人参果"))
        // Reserved transient test IDs. Only provisioned with bnbemu.testSupplies.
        list.add(ItemDefEntry(10003, 1, "Item/ItemWildGinsengC", desc = "火龙果（测试无限）"))
        list.add(ItemDefEntry(10004, 1, "Item/ShieldC", desc = "绿色自动盾牌（测试无限）"))
        // Red-preset NPCs look this up by name at 599DD5 before stocking
        // their native inventory. A missing definition silently skips Spring.
        list.add(ItemDefEntry(10005, 1, "Item/Spring", desc = "弹簧"))
        // CN324 consumable definitions. Its dart ID 2 collides with the
        // local Dizini character card, so use a separate local item ID.
        list.add(ItemDefEntry(75, 1, "Item/ItemCan", param = 2, desc = "超级罐头"))
        list.add(ItemDefEntry(10010, 1, "Item/ItemDart", skinId = 3, param = 7, desc = "飞镖"))
        list.add(ItemDefEntry(20243, 1, "Item/Sensor", param = 5, desc = "雷达"))
        list.add(ItemDefEntry(60278, 1, "Item/RemoteBomb", param = 0, desc = "遥控器"))
        list.add(ItemDefEntry(10024, 1, "Item/HollowBomb", param = 0, desc = "透明水泡"))
        list.add(ItemDefEntry(90177, 1, "Drill", param = 0, desc = "电钻"))
        list.add(ItemDefEntry(74, 1, "Item/ItemSpanner", param = 0, desc = "扳手"))
        list.add(ItemDefEntry(76, 1, "Item/ItemMotor", param = 0, desc = "发动机"))
        list.add(ItemDefEntry(77, 1, "Item/ItemPotion", param = 0, desc = "解毒药"))
        list.add(ItemDefEntry(78, 1, "Item/ItemScanner", param = 0, desc = "透视镜"))
        // 激活蓝妹妹 (Posh Evie / LuxuryMarid, ID 10) 角色进阶卡:
        // 对应 Item 2082: Category = 16 (0x10), skinId = 10, targetCharId = 4 (Marid)
        val idx2082 = list.indexOfFirst { it.itemId == 2082 }
        if (idx2082 != -1) {
            list[idx2082] = list[idx2082].copy(
                category = 0x10,
                skinId = 10,
                targetCharId = 4,
            )
        }
        list.map { it.copy(desc = bnbemu.data.LocalChineseNames.item(it.desc, it.name, it.category, it.itemId)) }
    }

    /**
     * 缓存的默认已加密数据块 (避免进频道时重复计算 46KB)
     */
    val CACHED_BLOB: ByteArray by lazy {
        encode(DEFAULT_ITEMS)
    }
}

/**
 * 进频道批次回应: 物品唯一定义表 (Opcode 60, S→C)
 */
fun ItemDef(): PKT = ItemDef(null)

fun ItemDef(items: List<ItemDefEntry>?) = PKT {
    val blob = if (items == null) ItemDef.CACHED_BLOB else ItemDef.encode(items)
    Encode4(blob.size)
    EncodeBuffer(blob)
}
