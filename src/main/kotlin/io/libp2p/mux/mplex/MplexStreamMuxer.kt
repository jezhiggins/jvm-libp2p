package io.libp2p.mux.mplex

import io.libp2p.core.P2PAbstractChannel
import io.libp2p.core.mplex.MplexFrameCodec
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.mux.StreamMuxerDebug
import io.libp2p.etc.events.MuxSessionFailed
import io.libp2p.etc.events.MuxSessionInitialized
import io.libp2p.mux.MuxHandler
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import java.util.concurrent.CompletableFuture

class MplexStreamMuxer : StreamMuxer, StreamMuxerDebug {
    override val announce = "/mplex/6.7.0"
    override val matcher: ProtocolMatcher =
        ProtocolMatcher(Mode.STRICT, announce)
    override var muxFramesDebugHandler: ChannelHandler? = null

    override fun initChannel(ch: P2PAbstractChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> {
        val muxSessionFuture = CompletableFuture<StreamMuxer.Session>()
        ch.pushHandler(MplexFrameCodec())
        muxFramesDebugHandler?.also { ch.pushHandler(it) }
        ch.pushHandler("MuxerSessionTracker", object : ChannelInboundHandlerAdapter() {
            override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                when (evt) {
                    is MuxSessionInitialized -> {
                        muxSessionFuture.complete(evt.session)
                        ctx.pipeline().remove(this)
                    }
                    is MuxSessionFailed -> {
                        muxSessionFuture.completeExceptionally(evt.exception)
                        ctx.pipeline().remove(this)
                    }
                    else -> super.userEventTriggered(ctx, evt)
                }
            }
        })
        ch.addHandlerBefore("MuxerSessionTracker", "MuxHandler", MuxHandler())
        return muxSessionFuture
    }
}