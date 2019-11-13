package io.libp2p.transport.tcp

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.transport.Transport
import io.libp2p.transport.NullConnectionUpgrader
import io.libp2p.transport.TransportTests
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

@Tag("tcp-transport")
class TcpTransportTest : TransportTests() {
    override fun makeTransport(): Transport {
        return TcpTransport(NullConnectionUpgrader())
    }

    override fun localAddress(portNumber: Int): Multiaddr {
        return Multiaddr("/ip4/127.0.0.1/tcp/$portNumber")
    }

    companion object {
        @JvmStatic
        fun validMultiaddrs() = listOf(
            "/dnsaddr/ipfs.io/tcp/97",
            "/ip4/1.2.3.4/tcp/1234",
            "/ip4/0.0.0.0/tcp/1234",
            "/ip4/1.2.3.4/tcp/0",
            "/ip4/0.0.0.0/tcp/1234",
            "/ip6/fe80::6f77:b303:aa6e:a16/tcp/42"
        ).map { Multiaddr(it) }

        @JvmStatic
        fun invalidMultiaddrs() = listOf(
            "/dnsaddr/ipfs.io/udp/97",
            "/ip4/1.2.3.4/tcp/1234/ws",
            "/ip4/1.2.3.4/udp/42",
            "/unix/a/file/named/tcp"
        ).map { Multiaddr(it) }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validMultiaddrs")
    fun `TcpTransport supports`(addr: Multiaddr) {
        assert(transportUnderTest.handles(addr))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMultiaddrs")
    fun `TcpTransport does not support`(addr: Multiaddr) {
        assert(!transportUnderTest.handles(addr))
    }
}
