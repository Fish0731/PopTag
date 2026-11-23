package bnbemu.server

import bnbemu.netty.ClientSocket
import bnbemu.packet.RosterEntry
import bnbemu.packet.SessionEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.Locale
import java.util.Properties

data class LocalPlayerRecord(val accountKey: String, val name: String, val userId: Int)

internal fun localPlayerDisplayEntries(current: List<LocalPlayerRecord>, history: List<LocalPlayerRecord>): List<LocalPlayerRecord> =
    (current + history)
        .distinctBy {it.accountKey.lowercase(Locale.ROOT)}
        .distinctBy {it.userId}
        .distinctBy {it.name.lowercase(Locale.ROOT)}
        .take(11)

/** Login history, not an online-presence list. No passwords or sample accounts. */
class LocalPlayerHistory(private val path: Path) {
    private val records = linkedMapOf<String,LocalPlayerRecord>()
    init {
        if (Files.exists(path)) {
            val p=Properties().apply { Files.newInputStream(path).use { load(it) } }
            repeat(p.getProperty("count","0").toInt()) { i ->
                val entry=LocalPlayerRecord(p.getProperty("$i.account"),p.getProperty("$i.name"),p.getProperty("$i.id").toInt())
                records[entry.accountKey.lowercase(Locale.ROOT)]=entry
            }
        }
    }
    @Synchronized fun record(entry: LocalPlayerRecord) {
        require(entry.accountKey.isNotBlank() && entry.name.isNotBlank())
        val key=entry.accountKey.lowercase(Locale.ROOT)
        if(records[key]==entry) return
        val next=LinkedHashMap(records).apply { put(key,entry) }
        val p=Properties().apply {
            setProperty("count",next.size.toString())
            next.values.forEachIndexed { i,e ->
                setProperty("$i.account",e.accountKey);setProperty("$i.name",e.name);setProperty("$i.id",e.userId.toString())
            }
        }
        Files.createDirectories(path.parent)
        val temporary=Files.createTempFile(path.parent,"players-",".tmp")
        try {
            Files.newOutputStream(temporary).use { p.store(it,"Local login history; not online status") }
            try { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING) }
            records.clear();records.putAll(next)
        } finally { Files.deleteIfExists(temporary) }
    }
    @Synchronized fun list(): List<LocalPlayerRecord> = records.values.toList()
}

object LocalPlayers {
    private val hiddenPath=Path.of(System.getProperty("bnbemu.accountsDir","data/local-accounts"),"login-history","hidden-accounts.txt")
    private val hiddenAccounts=if(Files.exists(hiddenPath)) Files.readAllLines(hiddenPath).filter {it.isNotBlank()}.map {it.lowercase(Locale.ROOT)}.toSet() else emptySet()
    private val history=LocalPlayerHistory(Path.of(System.getProperty("bnbemu.accountsDir","data/local-accounts"),"login-history","players.properties"))
    fun remember(user: User) {
        if(user.accountKey.lowercase(Locale.ROOT) !in hiddenAccounts) history.record(LocalPlayerRecord(user.accountKey,user.name,user.id))
    }
    const val MAX_SESSION_ENTRIES = 11
    fun entries(c: ClientSocket): List<LocalPlayerRecord> {
        c.users.forEach(::remember)
        val current = c.users.map { LocalPlayerRecord(it.accountKey, it.name, it.id) }
        val online = Lobby.onlineUsers().map { LocalPlayerRecord(it.accountKey, it.name, it.id) }
        return localPlayerDisplayEntries(
            current + online,
            history.list().filter { it.accountKey.lowercase(Locale.ROOT) !in hiddenAccounts }
        )
    }
    fun sessionEntries(c: ClientSocket): List<SessionEntry> = entries(c).filter {it.accountKey.lowercase(Locale.ROOT) !in hiddenAccounts}.map { e ->
        val level=c.users.firstOrNull { it.accountKey.equals(e.accountKey,true) }?.level
            ?: LocalEconomy.progress(LocalShop.store.account(e.accountKey).totalExperience).first
        SessionEntry(e.name,level,listItemBg=localNamePlateStyle(LocalShop.store.account(e.accountKey)))
    }
    fun rosterEntries(c: ClientSocket): List<RosterEntry> = entries(c).map { RosterEntry(it.name,it.userId) }
}
