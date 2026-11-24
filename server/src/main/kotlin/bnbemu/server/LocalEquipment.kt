package bnbemu.server

import bnbemu.packet.*

// Room op100's accessory field feeds CSessionContext slot+0x1D8,
// then CGOGameBomber+0x480. It is the ItemDef category-14 param, not item ID.
fun localBombStyle(account: LocalShopAccount, inventory: Int = 0): Int =
    account.equipment[inventory].orEmpty().toSortedMap().values
        .filter { it in account.items }
        .mapNotNull { id -> ItemDef.DEFAULT_ITEMS.firstOrNull { it.itemId == id && it.category == 14 } }
        .lastOrNull()?.param?.coerceAtLeast(0) ?: 0

fun localBombStyle(user: User?): Int =
    if (user == null) 0 else localBombStyle(LocalShop.store.account(user.accountKey))

// S72 listItemBg and S254 v8 both select IDDeco by category-18 skin index.
// Never send the owned item ID (e.g. 2459) in place of the style (19).
fun localNamePlateStyle(account: LocalShopAccount): Int =
    account.equipment[0].orEmpty().toSortedMap().values.filter { it in account.items }
        .mapNotNull { id -> ItemDef.DEFAULT_ITEMS.firstOrNull { it.itemId == id && it.category == 18 } }
        .lastOrNull()?.skinId?.coerceAtLeast(0) ?: 0

fun localNamePlateStyle(user: User?): Int =
    if (user == null) 0 else localNamePlateStyle(LocalShop.store.account(user.accountKey))

/** Full owned + equipped inventory. Slot 255 is a bag entry, not an equipped slot. */
fun localInventoryEntries(
    account: LocalShopAccount, inventory: Int,
    defaults: List<UserInventoryEntry> = DEFAULT_USER_INVENTORY_ITEMS,
    matchSupplies: List<UserInventoryEntry> = emptyList(),
): List<UserInventoryEntry> {
    val definitions = ItemDef.DEFAULT_ITEMS.associateBy { it.itemId }
    fun entry(id: Int, slot: Int) = UserInventoryEntry(
        itemId = id, slot = slot, param = account.items.getValue(id),
        token = definitions[id]?.name ?: "", expireDate = ByteArray(8), duration = 2400000
    )
    // The native match inventory can only represent one stock record per item ID.
    // Reserve the round-provided stack where IDs overlap; the purchased stack
    // stays in the account and is restored when the lobby inventory is sent.
    val suppliedIds = matchSupplies.map { it.itemId }.toSet()
    val reservedHotbarSlots = matchSupplies.filter { it.slot != 255 }.map { it.slot }.toSet()
    val expansionId = LocalShopStore.BAG_EXPANSION_ID
    val equipped = account.equipment[inventory].orEmpty().toSortedMap().filter { (slot, id) ->
        id in account.items && id != expansionId && id !in suppliedIds &&
            (definitions[id]?.category != 1 || slot !in reservedHotbarSlots)
    }
    // Native EquipItem creates the owned instance too. Sending slot255 for that
    // same cosmetic first creates a second bag icon when the profile opens.
    val bagExpanded = expansionId in account.items
    val inventoryDefaults = defaults.mapNotNull {
        when {
            bagExpanded && it.slot == 13 && it.itemId == 10006 -> entry(expansionId, 13)
            it.itemId in account.items -> null
            else -> it
        }
    }
    return inventoryDefaults + matchSupplies +
        account.items.keys.filter { it != expansionId && it !in equipped.values && it !in suppliedIds }.sorted().map { entry(it, 255) } +
        equipped.mapNotNull { (slot, id) ->
            if (id in account.items) entry(id, slot) else null
        }
}

fun localUserInventory(user: User?, practice: Boolean = false, inGame: Boolean = false) = UserInventory(
    (0..1).map { inventory ->
        // A single login must not duplicate its owned items into the unused 2P bag.
        val owner = if (inventory == 0) user else user?.c?.users?.getOrNull(inventory)
        val defaults = if (practice) DEFAULT_PRACTICE_INVENTORY_ITEMS else DEFAULT_USER_INVENTORY_ITEMS
        val supplies = if (inGame && (inventory == 0 || owner != null)) matchSupplyEntries() else emptyList()
        if (owner == null) defaults + supplies else {
            val character = if (owner.selectedBomber == 10) 4 else owner.selectedBomber.coerceIn(0, 7)
            val names = listOf("Dao", "Dizini", "Mos", "Ethi", "Marid", "Bazzi", "Uni", "Kephi")
            val selectedDefaults = defaults.map {
                if (it.slot == 15) UserInventoryEntry(character + 1, slot = 15, param = 20,
                    token = names[character], expireDate = ByteArray(8), status = 192) else it
            }
            localInventoryEntries(LocalShop.store.account(owner.accountKey), 0, selectedDefaults, supplies)
        }
    }
)

// S198 needs both the owned entry and the equipped entry for the practice needle.
fun matchSupplyEntries(mode: Int = Room.localGameMode): List<UserInventoryEntry> =
    if (mode == 15) listOf(
        UserInventoryEntry(10000, slot = 255, param = 1, token = "Item/ItemNiddle", expireDate = ByteArray(8), duration = 2400000),
        UserInventoryEntry(10000, slot = 0, param = 1, token = "Item/ItemNiddle", expireDate = ByteArray(8), duration = 2400000)
    ) else emptyList()

/**
 * 针的持有物初始化 (op 274 UserItemUpdate)。
 *
 * 逆向 (CA.exe 0x4FCCBF, op274/op320 共用): 该包经 0x6711e5/0x65d55f 在背包向量
 * [CUserInventoryMan+8] 创建 0xC8 字节持有物对象 —— 这是 op198 type8 热键穿戴
 * (FindOwnedItemById 闸门) 的前置条件。quantity 即道具栏堆叠数。
 * 练习模式为当前连接的每名玩家单独初始化。
 */
@JvmOverloads fun needleStockPackets(playerCount: Int = 1, mode: Int = Room.localGameMode) = (0 until playerCount.coerceIn(1,2)).flatMap { player ->
    matchSupplyEntries(mode).filter { it.slot == 255 }.map {
        UserItemUpdate(slotType = player, itemId = it.itemId, quantity = it.param, expireHours = 0, flag1 = 255, flag2 = 0)
    }
}

/** The EN slot accessory packet carries resource indexes, not owned item ids. */
fun localAppearanceDefinitions(user: User?): List<ItemDefEntry> {
    if (user == null) return emptyList()
    val account = LocalShop.store.account(user.accountKey)
    val ids = account.equipment[0].orEmpty().toSortedMap().values.filter { it in account.items }.distinct()
    return ids.mapNotNull { id -> ItemDef.DEFAULT_ITEMS.firstOrNull { it.itemId == id } }
}
