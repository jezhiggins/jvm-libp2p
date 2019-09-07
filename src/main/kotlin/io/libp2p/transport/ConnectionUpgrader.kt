package io.libp2p.transport

import io.libp2p.core.multistream.Multistream
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.getP2PChannel
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

/**
 * ConnectionUpgrader is a utility class that Transports can use to shim secure channels and muxers when those
 * capabilities are not provided natively by the transport.
 */
class ConnectionUpgrader(
    private val secureChannels: List<SecureChannel>,
    private val muxers: List<StreamMuxer>
) {

    var beforeSecureHandler: ChannelHandler? = null
    var afterSecureHandler: ChannelHandler? = null

    fun establishSecureChannel(ch: Channel): CompletableFuture<SecureChannel.Session> {
        val multistream = Multistream.create(secureChannels)
        beforeSecureHandler?.also { ch.pipeline().addLast(it) }
        val ret = multistream.initChannel(ch.getP2PChannel())
        afterSecureHandler?.also { ch.pipeline().addLast(it) }
        return ret
    }

    fun establishMuxer(ch: Channel): CompletableFuture<StreamMuxer.Session> {

        val multistream = Multistream.create(muxers)
        return multistream.initChannel(ch.getP2PChannel())
    }
}