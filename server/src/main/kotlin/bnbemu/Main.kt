package bnbemu

import bnbemu.netty.NettyServer
import bnbemu.netty.UdpRelay
import java.util.concurrent.atomic.AtomicInteger

private val nextUserIdCounter = AtomicInteger(1)

var nextUserId: Int
    get() = nextUserIdCounter.get()
    set(value) {
        nextUserIdCounter.set(value)
    }

fun allocateNextUserId(): Int = nextUserIdCounter.getAndIncrement()

fun main() {
    NettyServer.start()
    UdpRelay.start()
}
