package bnbemu.netty

import bnbemu.ACP
import bnbemu.handler.decodeLocalLoginAccounts
import bnbemu.handler.decodeLocalChannelMembers
import bnbemu.packet.*
import bnbemu.server.*
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalTwoPlayerTest {
    @Test fun randomCharacterRequestAcknowledgesTheSecondPlayer() {
        OpcodeManager.recvOps
        val socket = ClientSocket()
        val channel = EmbeddedChannel(socket)
        val seat = Room.slots[1]
        val originalUser = seat.user
        val originalBomber = seat.bomber
        try {
            val first = User(socket, 29231, "random-first")
            val second = User(socket, 29232, "random-second")
            socket.users.addAll(listOf(first, second))
            first.selectedBomber = 10
            second.selectedBomber = 6
            seat.user = second
            val request = Unpooled.buffer()
            SendPacketBase(request, 3).apply { Encode2(87); Encode1(1); Encode1(13) }
            channel.writeInbound(request)
            val reply = requireNotNull(channel.readOutbound<PKT>())
            assertEquals(105, OpcodeManager.getSendOp(reply::class.java))
            val body = Unpooled.wrappedBuffer(encode(reply))
            try {
                assertEquals(1, body.Decode1)
                assertEquals(RANDOM_BOMBER, body.Decode1)
                assertEquals(6, second.selectedBomber)
                assertEquals(RANDOM_BOMBER, seat.bomber.toInt())
                assertEquals(10, first.selectedBomber)
            } finally { body.release() }
        } finally {
            seat.user = originalUser
            seat.bomber = originalBomber
            channel.finishAndReleaseAll()
        }
    }

    @Test fun secondPlayerShopPurchaseChargesAndDeliversOnlyToSecondAccount() {
        OpcodeManager.recvOps
        val socket=ClientSocket();val channel=EmbeddedChannel(socket)
        val suffix=java.util.UUID.randomUUID().toString()
        try {
            socket.users.addAll(listOf(User(socket,29201,"wallet-one-$suffix"),User(socket,29202,"wallet-two-$suffix")))
            val first=socket.users[0];val second=socket.users[1]
            val product=ShopCatalog.DEFAULT_ITEMS.first {it.tail.price1 in 1..500000 && it.count==1}
            val enter=Unpooled.buffer();SendPacketBase(enter,3).apply {Encode2(315);Encode1(1)}
            channel.writeInbound(enter)
            assertEquals(1,socket.shopPlayerIndex)
            while(channel.readOutbound<PKT>()!=null) {}
            val purchase=Unpooled.buffer();SendPacketBase(purchase,3).apply {Encode2(267);Encode4(product.prodId);Encode4(0);Encode1(0)}
            channel.writeInbound(purchase)
            assertEquals(3000000,LocalShop.store.account(first.accountKey).lucci)
            assertTrue(LocalShop.store.account(first.accountKey).items.isEmpty())
            val purchased=LocalShop.store.account(second.accountKey)
            assertEquals(3000000-product.tail.price1,purchased.lucci)
            assertEquals(1,purchased.items[product.itemId])
            val wallets=mutableListOf<Pair<Int,Int>>()
            while(true) {
                val packet=channel.readOutbound<PKT>() ?: break
                if(OpcodeManager.getSendOp(packet::class.java)==122) {
                    val b=Unpooled.wrappedBuffer(encode(packet))
                    try {wallets.add(b.Decode1 to b.Decode4)} finally {b.release()}
                }
            }
            assertEquals(listOf(1 to purchased.lucci),wallets)
        } finally {channel.finishAndReleaseAll()}
    }
    @Test fun secondPlayerGloveAndShoeRequestsPreserveTheirSlotAndLandBeforeExplosion() {
        OpcodeManager.recvOps
        val socket=ClientSocket();val channel=EmbeddedChannel(socket)
        val saved=Room.slots.map {Triple(it.user,it.isAI,it.meta.copyOf())};val mode=Room.localGameMode
        try {
            Room.reset();Room.localGameMode=15;Room.slots.forEach {it.user=null;it.isAI=false}
            socket.users.addAll(listOf(User(socket,29211,"movement-one"),User(socket,29212,"movement-two")))
            socket.users.forEachIndexed {i,u ->Room.slots[i].user=u}
            assertEquals(1,Room.resolveHumanSlot(socket,1))
            assertNull(Room.resolveHumanSlot(socket,255),"Ambiguous local sender must not become 1P")
            Room.startGame()
            while(channel.readOutbound<PKT>()!=null) {}
            for(action in listOf(0x01,0x41)) {
                val id=400+action
                Room.addBomb(1,5,5,bombId=id,broadcastInitialState=false)
                val deadline=System.nanoTime()+2_000_000_000L
                while(Room.getBomb(id)==null && System.nanoTime()<deadline) Thread.sleep(2)
                val b=Unpooled.buffer();SendPacketBase(b,3).apply {
                    Encode2(107);Encode1(action);Encode2(id);Encode1(5);Encode1(5);Encode1(5);Encode1(7);Encode2(500)
                }
                channel.writeInbound(b)
                var response: PKT?=null
                while(response==null && System.nanoTime()<deadline) {
                    val packet=channel.readOutbound<PKT>()
                    if(packet!=null && OpcodeManager.getSendOp(packet::class.java)==141) response=packet
                    else Thread.sleep(2)
                }
                assertNotNull(response)
                val wire=Unpooled.wrappedBuffer(encode(response!!))
                try {assertEquals(action,wire.Decode1);assertEquals(id,wire.Decode2)} finally {wire.release()}
                val bomb=Room.getBomb(id)!!
                assertTrue(bomb.isMoving)
                assertTrue(bomb.updateState(bomb.expireAt));assertEquals(1,bomb.state)
                assertTrue(bomb.updateState(bomb.expireAt));assertEquals(0,bomb.state)
            }
        } finally {
            Room.reset();Room.localGameMode=mode
            Room.slots.forEachIndexed {i,s ->s.user=saved[i].first;s.isAI=saved[i].second;saved[i].third.copyInto(s.meta)}
            channel.finishAndReleaseAll()
        }
    }
    @Test fun bothHumansOnOneConnectionCanKickAStarfishShell() {
        OpcodeManager.recvOps
        val socket=ClientSocket();val channel=EmbeddedChannel(socket)
        val saved=Room.slots.map {Triple(it.user,it.isAI,it.meta.copyOf())};val mode=Room.localGameMode
        try {
            Room.reset();Room.localGameMode=14;Room.slots.forEach {it.user=null;it.isAI=false}
            socket.users.addAll(listOf(User(socket,29131,"shell-one"),User(socket,29132,"shell-two")))
            socket.users.forEachIndexed {i,u -> Room.slots[i].user=u}
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single {it.value.name=="MonTurtle01"}.key)
            Room.onBoardReady()
            while(channel.readOutbound<PKT>()!=null) {}
            for(slot in listOf(0,1)) {
                val body=Unpooled.buffer();SendPacketBase(body,3).apply {
                    Encode2(168);Encode2(0);Encode1(2);Encode4(560);Encode4(280);Encode4(-1);Encode4(-1);Encode1(slot)
                }
                channel.writeInbound(body)
                val responses=mutableListOf<PKT>()
                while(true) {responses.add(channel.readOutbound<PKT>() ?: break)}
                val result=responses.single {OpcodeManager.getSendOp(it::class.java)==151}
                val wire=Unpooled.wrappedBuffer(encode(result))
                try {
                    assertEquals(14,wire.Decode2);assertEquals(0,wire.Decode2);assertEquals(22,wire.Decode1)
                    assertEquals(2,wire.Decode1);repeat(4){wire.Decode4};assertEquals(slot,wire.Decode1)
                    assertEquals(0,wire.readableBytes())
                } finally {wire.release()}
            }
            val invalid=Unpooled.buffer();SendPacketBase(invalid,3).apply {
                Encode2(168);Encode2(0);Encode1(2);Encode4(560);Encode4(280);Encode4(-1);Encode4(-1);Encode1(2)
            }
            channel.writeInbound(invalid)
            assertNull(channel.readOutbound<PKT>(),"A shared connection must not gain an unowned seat")
        } finally {
            Room.reset();Room.localGameMode=mode
            Room.slots.forEachIndexed {i,s ->s.user=saved[i].first;s.isAI=saved[i].second;saved[i].third.copyInto(s.meta)}
            channel.finishAndReleaseAll()
        }
    }
    @Test fun switchingProfilesImmediatelySelectsTheRequestedHumanAndWallet() {
        OpcodeManager.recvOps
        val socket=ClientSocket();val channel=EmbeddedChannel(socket)
        try {
            socket.users.addAll(listOf(User(socket,29101,"profile-one"),User(socket,29102,"profile-two")))
            socket.users[0].lucci=1900000;socket.users[1].lucci=3000000
            bnbemu.handler.sendUserInfo(socket,0);bnbemu.handler.sendUserInfo(socket,1)
            val names=mutableListOf<String>()
            val wallets=mutableListOf<Pair<Int,Int>>()
            while(true) {
                val packet=channel.readOutbound<PKT>() ?: break
                val data=Unpooled.wrappedBuffer(encode(packet))
                try {
                    when(OpcodeManager.getSendOp(packet::class.java)) {
                        122 -> wallets.add(data.Decode1 to data.Decode4)
                        73 -> {
                            assertEquals(0,data.Decode4);assertEquals(1,data.Decode4)
                            assertEquals(names.size,data.Decode1)
                            val token=ByteArray(data.Decode2);data.readBytes(token);names.add(decodeName(token))
                        }
                    }
                } finally {data.release()}
            }
            assertEquals(listOf("profile-one","profile-two"),names)
            assertEquals(listOf(0 to 1900000,1 to 3000000),wallets)
        } finally {channel.finishAndReleaseAll()}
    }
    @Test fun bothLobbyPanelsAndWalletCachesHaveACompleteRecordForEachLocalPlayer() {
        val socket=ClientSocket();val channel=EmbeddedChannel(socket)
        try {
            val users=listOf(User(socket,29051,"panel-cache-a"),User(socket,29052,"panel-cache-b"))
            users[0].apply {level=12;exp=10;lucci=1900000}
            users[1].apply {level=62;exp=500;lucci=3000000}
            val separate=users.map {encode(UserMapInfo(localUserMapEntry(it)))}
            val encodedFlags=byteArrayOf(0x5a,0x5a,0x5a)
            val panels=encode(UserMapInfo(users=users.map(::localUserMapEntry)))
            assertArrayEquals(separate[0]+encodedFlags+separate[1]+encodedFlags,panels)
            // Follow native 4E061C / 4E09D6 field reads, including enabled
            // feature47's per-member suffix, rather than trusting concatenation.
            val native=Unpooled.wrappedBuffer(panels)
            try {
                for(user in users) {
                    assertEquals(1,native.Decode1)
                    val token=ByteArray(native.Decode2);native.readBytes(token)
                    assertEquals(user.name,decodeName(token))
                    native.Decode1;native.Decode1;native.Decode4;native.Decode1;native.Decode4
                    assertEquals(user.level,native.Decode2)
                    repeat(4){native.Decode4};native.Decode1;native.Decode4;repeat(4){native.Decode2}
                    native.Decode4;native.Decode1
                    repeat(3){assertEquals(0,native.Decode1)}
                }
                assertEquals(0,native.readableBytes())
            } finally {native.release()}
            val singleCaches=users.map {encode(Unk20(it.selectedBomber,it))}
            val dual=encode(Unk20(users=users))
            assertEquals(2,(dual[0].toInt() and 255) xor 0x5a)
            assertArrayEquals(singleCaches[0].drop(1).toByteArray()+singleCaches[1].drop(1).toByteArray(),dual.drop(1).toByteArray())
            val body=Unpooled.wrappedBuffer(dual)
            try {
                body.readerIndex(15);assertEquals(1900000,body.Decode4)
                body.readerIndex(singleCaches[0].size-1+15);assertEquals(3000000,body.Decode4)
            } finally {body.release()}
        } finally {channel.finishAndReleaseAll()}
    }
    @Test fun realTwoPlayerChannelHandshakeSkipsEachMembersTwelveByteTrailer() {
        val hex="58 A5 61 4C 1E E2 52 75 19 9D 46 96 CA 76 05 96 CA 53 95 00 00 00 00 71 B1 85 89 A5 61 7B 03 B8 25 45 1D A6 58 96 CA 0A 13 96 CA 53 95 00 00 00 00 71 B1 85 89 5A 5B 84 E9 60 B7 84 E9 60 B7 5B"
        val raw=hex.split(' ').map {it.toInt(16).toByte()}.toByteArray()
        val b=Unpooled.wrappedBuffer(raw)
        val members=try {
            val result=b.decodeLocalChannelMembers()!!
            assertEquals(listOf(9616,22918),result.map {it.userId})
            assertEquals("潜在游鱼",result.first().name)
            assertNotEquals(result[0].name,result[1].name)
            assertEquals(0,b.readableBytes())
            result
        } finally {b.release()}
        val singleRaw=raw.take(27).toByteArray()+raw.takeLast(11).toByteArray()
        singleRaw[0]=0x5b
        val single=Unpooled.wrappedBuffer(singleRaw)
        try {assertEquals(listOf(members[0]),single.decodeLocalChannelMembers());assertEquals(0,single.readableBytes())} finally {single.release()}
        for(length in listOf(1,12,30,raw.size-1)) {
            val partial=Unpooled.wrappedBuffer(raw.copyOf(length))
            try {assertNull(partial.decodeLocalChannelMembers());assertEquals(0,partial.readerIndex())} finally {partial.release()}
        }
        OpcodeManager.recvOps
        val socket=ClientSocket();val channel=EmbeddedChannel(socket)
        try {
            val incoming=Unpooled.buffer();SendPacketBase(incoming,3).Encode2(7);incoming.writeBytes(raw)
            channel.writeInbound(incoming)
            assertTrue(channel.isActive)
            assertEquals(members.map {it.userId},socket.users.map {it.id})
            assertEquals(2,socket.users.map {it.accountKey}.distinct().size)
            val opcodes=mutableListOf<Int>()
            while(true) {val packet=channel.readOutbound<PKT>() ?: break;opcodes.add(OpcodeManager.getSendOp(packet::class.java))}
            assertEquals(listOf(48,13,14,97,122,87,423),opcodes)
        } finally {channel.finishAndReleaseAll()}
    }
    private fun encode(packet: PKT): ByteArray {
        val b=Unpooled.buffer()
        try {packet.encode(SendPacketBase(b,3));return ByteArray(b.readableBytes()).also {b.readBytes(it)}} finally {b.release()}
    }
    @Test fun twoPassportIdentitiesKeepTheirOrderAndProduceTwoCompleteMembers() {
        val accounts=listOf(PassportServer.PassportAccount("two-a","测试甲",29001),PassportServer.PassportAccount("two-b","测试乙",29002))
        accounts.forEach {PassportServer.ACCOUNTS[it.username]=it}
        val b=Unpooled.buffer()
        try {
            val out=SendPacketBase(b,3);out.Encode1(2)
            for(a in accounts) {
                val raw=("NGP"+a.username).toByteArray(ACP);out.Encode4(raw.size)
                var i=0
                while(i+4<=raw.size) {out.Encode4((raw[i].toInt() and 255) or ((raw[i+1].toInt() and 255) shl 8) or ((raw[i+2].toInt() and 255) shl 16) or ((raw[i+3].toInt() and 255) shl 24));i+=4}
                if(raw.size-i>=2) {out.Encode2((raw[i].toInt() and 255) or ((raw[i+1].toInt() and 255) shl 8));i+=2}
                if(i<raw.size) out.Encode1(raw[i].toInt() and 255)
            }
            assertEquals(accounts,b.decodeLocalLoginAccounts())
            assertEquals(0,b.readerIndex())
            val one=accounts.map {encode(AskFirstInfoResult(listOf(it)))}
            val two=encode(AskFirstInfoResult(accounts))
            assertEquals(2,(two[3].toInt() and 255) xor 0x5a)
            assertArrayEquals(one[0].copyOfRange(4,one[0].size-7)+one[1].copyOfRange(4,one[1].size-7),two.copyOfRange(4,two.size-7))
            assertArrayEquals(one[0].takeLast(7).toByteArray(),two.takeLast(7).toByteArray())
            val truncated=b.copy(0,3)
            try {assertNull(truncated.decodeLocalLoginAccounts());assertEquals(0,truncated.readerIndex())} finally {truncated.release()}
        } finally {b.release();accounts.forEach {PassportServer.ACCOUNTS.remove(it.username)}}
    }
    @Test fun actualTwoPlayerRoomDispatchCreatesTwoHumansOnOneConnection() {
        OpcodeManager.recvOps
        val socket=ClientSocket();val channel=EmbeddedChannel(socket)
        val saved=Room.slots.map {Triple(it.user,it.isAI,it.meta.copyOf())};val mode=Room.localGameMode
        socket.users.addAll(listOf(User(socket,29011,"two-seat-a"),User(socket,29012,"two-seat-b")))
        try {
            val b=Unpooled.buffer();SendPacketBase(b,3).apply {
                Encode2(75);EncodeStr("two-local");EncodeStr("");Encode1(1);Encode1(2)
                Encode1(6);Encode1(5);Encode1(0);Encode4(0)
            }
            channel.writeInbound(b)
            assertSame(socket.users[0],Room.slots[0].user)
            assertSame(socket.users[1],Room.slots[1].user)
            assertFalse(Room.slots[1].isAI)
            assertEquals(0,Room.slots[1].color.toInt())
            assertEquals(0x10,(encode(PracticeSlot104(1,0))[0].toInt() and 255) xor 0x5a)
            assertEquals(2,Room.activeSlotCount)
            assertEquals(1,Room.uniqueClients.count())
            val choose=Unpooled.buffer();SendPacketBase(choose,3).apply {Encode2(87);Encode1(1);Encode1(5)}
            channel.writeInbound(choose)
            assertEquals(6,socket.users[0].selectedBomber)
            assertEquals(5,socket.users[1].selectedBomber)
            val sync=Unpooled.buffer();SendPacketBase(sync,3).apply {Encode2(73);Encode1(2);Encode1(3);Encode1(4)}
            channel.writeInbound(sync)
            assertEquals(3,socket.users[0].selectedBomber)
            assertEquals(4,socket.users[1].selectedBomber)
            val slots=mutableSetOf<Int>()
            while(true) {
                val packet=channel.readOutbound<PKT>() ?: break
                if(OpcodeManager.getSendOp(packet::class.java)==100) slots.add((encode(packet)[0].toInt() and 255) xor 0x5a)
            }
            assertEquals(setOf(0,1),slots)
            assertEquals(2,needleStockPackets(2, mode = 15).size)
        } finally {
            Room.reset();Room.localGameMode=mode
            Room.slots.forEachIndexed {i,s ->s.user=saved[i].first;s.isAI=saved[i].second;saved[i].third.copyInto(s.meta)}
            channel.finishAndReleaseAll()
        }
    }
    @Test fun onlyTheOwningPlayersPurchasedStockDecrements() {
        val client = ClientSocket()
        val channel = EmbeddedChannel(client)
        val mode = Room.localGameMode
        val saved = Room.slots.take(2).map { it.user }
        val suffix = System.nanoTime()
        val first = User(client, 29321, "consumable-first-$suffix")
        val second = User(client, 29322, "consumable-second-$suffix")
        val needle = ShopCatalog.DEFAULT_ITEMS.single { it.prodId == 91_000_001 }
        val shield = ShopCatalog.DEFAULT_ITEMS.single { it.prodId == 91_000_003 }
        val store = LocalShop.store
        try {
            client.users.addAll(listOf(first, second))
            Room.slots[0].user = first
            Room.slots[1].user = second
            for (user in client.users) {
                store.purchase(user.accountKey, needle)
                store.purchase(user.accountKey, shield)
                store.changeEquipment(user.accountKey, 0, listOf(0 to needle.itemId, 1 to shield.itemId))
            }
            fun use(inventory: Int, itemId: Int, previous: Int) {
                val body = Unpooled.buffer()
                SendPacketBase(body, 3).apply {
                    Encode2(431); Encode1(inventory); Encode4(itemId); Encode2(previous)
                }
                channel.writeInbound(body)
            }
            fun stock(user: User, itemId: Int) = store.account(user.accountKey).items[itemId]

            Room.localGameMode = 16
            use(1, shield.itemId, 10)
            assertEquals(10, stock(second, shield.itemId), "Lobby reports do not change stock")
            Room.startGame()
            use(1, shield.itemId, 10)
            use(1, shield.itemId, 10)
            assertEquals(10, stock(first, shield.itemId))
            assertEquals(9, stock(second, shield.itemId))
            assertNull(channel.readOutbound<PKT>(), "Do not reset live inventory when persisting a use")

            Room.localGameMode = 15
            Room.startGame()
            use(1, needle.itemId, 10)
            assertEquals(10, stock(second, needle.itemId), "Practice uses its own unlimited needle")
            use(0, shield.itemId, 10)
            assertEquals(9, stock(first, shield.itemId))
        } finally {
            Room.reset()
            Room.localGameMode = mode
            saved.forEachIndexed { slot, user -> Room.slots[slot].user = user }
            Room.resetMatchState()
            channel.finishAndReleaseAll()
        }
    }
}
