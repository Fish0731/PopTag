package bnbemu.netty

import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PassportServerChannelTest {
    @Test
    fun `createServerChannel selects NIO channel when epoll disabled`() {
        val ch = PassportServer.createServerChannel(useEpoll = false)
        try {
            assertTrue(ch is NioServerSocketChannel)
        } finally {
            ch.unsafe().closeForcibly()
        }
    }

    @Test
    fun `createServerChannel matches runtime Epoll availability`() {
        val ch = PassportServer.createServerChannel()
        try {
            if (Epoll.isAvailable()) {
                assertTrue(ch is EpollServerSocketChannel)
            } else {
                assertTrue(ch is NioServerSocketChannel)
            }
        } finally {
            ch.unsafe().closeForcibly()
        }
    }
}
