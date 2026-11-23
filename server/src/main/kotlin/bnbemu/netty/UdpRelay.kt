package bnbemu.netty

import bnbemu.NetConfig
import bnbemu.server.Room
import bnbemu.server.UdpPeers
import bnbemu.server.User
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOption
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollDatagramChannel
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * Server-side UDP P2P relay (UDP 4151..4160, one port per player slot 0..9).
 *
 * Supports both LAN clients and public-internet clients behind Symmetric NAT / CGNAT
 * by falling back to unique source-IP slot matching and learning per-(channel, slot)
 * NAT pinhole return addresses.
 */
object UdpRelay {
    const val BASE_PORT = 4151
    const val SLOTS = 10

    private data class ReturnEntry(val userId: Int, val addr: InetSocketAddress)

    private val channels = arrayOfNulls<Channel>(SLOTS)
    private val channelReturn = Array(SLOTS) { arrayOfNulls<ReturnEntry>(SLOTS) }
    private val forwarded = AtomicLong()
    private val dropped = AtomicLong()

    @ChannelHandler.Sharable
    private object IdleCloseHandler : ChannelInboundHandlerAdapter() {
        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (evt is IdleStateEvent && evt.state() == IdleState.READER_IDLE) {
                ctx.close()
                return
            }
            super.userEventTriggered(ctx, evt)
        }
    }

    fun datagramChannelClass(): Class<out DatagramChannel> =
        if (Epoll.isAvailable()) EpollDatagramChannel::class.java else NioDatagramChannel::class.java

    fun installIdleTimeout(ch: Channel?, readTimeoutSec: Int) {
        if (ch == null || readTimeoutSec <= 0) return
        if (ch.pipeline().get("poptagIdle") == null) {
            ch.pipeline().addFirst("poptagIdleClose", IdleCloseHandler)
            ch.pipeline().addFirst("poptagIdle", IdleStateHandler(readTimeoutSec, 0, 0))
        }
    }

    fun clearUser(userId: Int) {
        for (a in 0 until SLOTS) {
            for (b in 0 until SLOTS) {
                val e = channelReturn[a][b]
                if (e != null && e.userId == userId) {
                    channelReturn[a][b] = null
                }
            }
        }
        UdpPeers.clearUser(userId)
    }

    fun start() {
        val epoll = Epoll.isAvailable()
        val chClass = datagramChannelClass()
        for (i in 0 until SLOTS) {
            val port = BASE_PORT + i
            Bootstrap().apply {
                group(NettyServer.workerGroup)
                channel(chClass)
                option(ChannelOption.SO_REUSEADDR, true)
                handler(RelayHandler(i))
            }.bind("0.0.0.0", port).addListener { f ->
                if (f.isSuccess) {
                    channels[i] = (f as ChannelFuture).channel()
                } else {
                    println("UdpRelay bind failed on port $port: ${f.cause()}")
                }
            }
        }
        println(
            "UdpRelay listening on UDP 0.0.0.0:$BASE_PORT..${BASE_PORT + SLOTS - 1} " +
                "(transport=${if (epoll) "epoll" else "nio"}, ServerIP=${NetConfig.serverIp})"
        )
    }

    fun endpointFor(slot: Int): UdpPeers.Endpoint =
        UdpPeers.Endpoint(NetConfig.serverIpInt, BASE_PORT + slot)

    fun endpointForClient(slot: Int, c: ClientSocket?): UdpPeers.Endpoint =
        UdpPeers.Endpoint(NetConfig.resolveServerIpIntFor(c), BASE_PORT + slot)

    private fun slotOfUserId(uid: Int): Int {
        val slots = Room.slots
        for (i in slots.indices) {
            val u = slots[i].user
            if (u != null && !slots[i].isAI && u.id == uid) return i
        }
        return -1
    }

    private fun tcpRemoteIpOf(u: User?): InetAddress? =
        (u?.c?.ch?.remoteAddress() as? InetSocketAddress)?.address

    internal fun slotOfSender(sender: InetSocketAddress?): Int {
        if (sender == null) return -1
        val uid = UdpPeers.userIdOf(sender)
        if (uid != null) {
            val exact = slotOfUserId(uid)
            if (exact >= 0) return exact
        }
        val senderIp = sender.address ?: return -1
        var matchedSlot = -1
        var matchCount = 0
        for (i in Room.slots.indices) {
            val s = Room.slots[i]
            val u = s.user ?: continue
            if (s.isAI) continue
            val obs = UdpPeers.observedOf(u.id)
            val ipMatch = (obs != null && senderIp == obs.address) ||
                (tcpRemoteIpOf(u)?.let { senderIp == it } == true)
            if (ipMatch) {
                matchedSlot = i
                matchCount++
            }
        }
        return if (matchCount == 1) matchedSlot else -1
    }

    internal fun addrOfSlot(outChannelSlot: Int, targetSlot: Int): InetSocketAddress? {
        val slot = Room.slots.getOrNull(targetSlot) ?: return null
        val u = slot.user ?: return null
        if (outChannelSlot in 0 until SLOTS && targetSlot in 0 until SLOTS) {
            val learned = channelReturn[outChannelSlot][targetSlot]
            if (learned != null && learned.userId == u.id) return learned.addr
        }
        return UdpPeers.observedOf(u.id)
    }

    internal fun recordReturn(targetSlot: Int, srcSlot: Int, userId: Int, sender: InetSocketAddress) {
        if (targetSlot in 0 until SLOTS && srcSlot in 0 until SLOTS) {
            channelReturn[targetSlot][srcSlot] = ReturnEntry(userId, sender)
        }
    }

    private class RelayHandler(private val targetSlot: Int) : SimpleChannelInboundHandler<DatagramPacket>() {
        override fun channelRead0(ctx: ChannelHandlerContext, pkt: DatagramPacket) {
            val sender = pkt.sender()
            val srcSlot = slotOfSender(sender)
            if (srcSlot !in 0 until SLOTS) {
                dropped.incrementAndGet()
                return
            }
            Room.slots.getOrNull(srcSlot)?.user?.let { srcUser ->
                recordReturn(targetSlot, srcSlot, srcUser.id, sender)
            }
            val dst = addrOfSlot(srcSlot, targetSlot)
            val outCh = channels[srcSlot]
            if (dst == null || outCh == null || !outCh.isActive) {
                dropped.incrementAndGet()
                return
            }
            outCh.writeAndFlush(DatagramPacket(pkt.content().retainedDuplicate(), dst))
            forwarded.incrementAndGet()
        }
    }
}
