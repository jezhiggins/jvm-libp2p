package io.libp2p.pubsub

import io.libp2p.core.Stream
import io.libp2p.etc.types.LRUSet
import io.libp2p.etc.types.MultiSet
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.copy
import io.libp2p.etc.types.forward
import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.types.toHex
import io.libp2p.etc.util.P2PServiceSemiDuplex
import io.netty.channel.ChannelHandler
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
import pubsub.pb.Rpc
import java.util.Random
import java.util.concurrent.CompletableFuture

/**
 * Implements common logic for pubsub routers
 */
abstract class AbstractRouter : P2PServiceSemiDuplex(), PubsubRouter, PubsubRouterDebug {

    override var curTime: () -> Long by lazyVar { { System.currentTimeMillis() } }
    override var random by lazyVar { Random() }

    val peerTopics = MultiSet<PeerHandler, String>()
    private var msgHandler: (Rpc.Message) -> Unit = { }
    var maxSeenMessagesSizeSet = 10000
    var validator: PubsubMessageValidator = PubsubMessageValidator.nopValidator()
    val seenMessages by lazy { LRUSet.create<String>(maxSeenMessagesSizeSet) }
    val subscribedTopics = linkedSetOf<String>()
    val pendingRpcParts = linkedMapOf<PeerHandler, MutableList<Rpc.RPC>>()
    private var debugHandler: ChannelHandler? = null
    private val pendingMessagePromises = MultiSet<PeerHandler, CompletableFuture<Unit>>()

    protected fun getMessageId(msg: Rpc.Message): String = msg.from.toByteArray().toHex() + msg.seqno.toByteArray().toHex()

    override fun publish(msg: Rpc.Message): CompletableFuture<Unit> {
        return submitAsyncOnEventThread {
            if (getMessageId(msg) in seenMessages) {
                completedExceptionally(MessageAlreadySeenException("Msg: $msg"))
            } else {
                validator.validate(msg) // check ourselves not to be a bad peer
                seenMessages += getMessageId(msg)
                broadcastOutbound(msg)
            }
        }
    }

    protected open fun submitPublishMessage(toPeer: PeerHandler, msg: Rpc.Message): CompletableFuture<Unit> {
        addPendingRpcPart(toPeer, Rpc.RPC.newBuilder().addPublish(msg).build())
        val sendPromise = CompletableFuture<Unit>()
        pendingMessagePromises[toPeer] += sendPromise
        return sendPromise
    }

    /**
     * Submits a partial message for a peer.
     * Later message parts for each peer are merged and sent to the wire
     */
    protected fun addPendingRpcPart(toPeer: PeerHandler, msgPart: Rpc.RPC) {
        pendingRpcParts.getOrPut(toPeer, { mutableListOf() }) += msgPart
    }

    /**
     * Drains all partial messages for [toPeer] and returns merged message
     */
    protected fun collectPeerMessage(toPeer: PeerHandler): Rpc.RPC? {
        val msgs = pendingRpcParts.remove(toPeer) ?: emptyList<Rpc.RPC>()
        if (msgs.isEmpty()) return null

        val bld = Rpc.RPC.newBuilder()
        msgs.forEach { bld.mergeFrom(it) }
        return bld.build()
    }

    /**
     * Flushes all pending message parts for all peers
     * @see addPendingRpcPart
     */
    protected fun flushAllPending() {
        pendingRpcParts.keys.copy().forEach(::flushPending)
    }

    protected fun flushPending(peer: PeerHandler) {
        collectPeerMessage(peer)?.also {
            val future = send(peer, it)
            pendingMessagePromises.removeAll(peer)?.forEach {
                future.forward(it)
            }
        }
    }

    override fun addPeer(peer: Stream) {
        addNewStream(peer)
    }

    override fun addPeerWithDebugHandler(peer: Stream, debugHandler: ChannelHandler?) {
        this.debugHandler = debugHandler
        try {
            addPeer(peer)
        } finally {
            this.debugHandler = null
        }
    }

    override fun initChannel(streamHandler: StreamHandler) {
        with(streamHandler.stream) {
            pushHandler(ProtobufVarint32FrameDecoder())
            pushHandler(ProtobufVarint32LengthFieldPrepender())
            pushHandler(ProtobufDecoder(Rpc.RPC.getDefaultInstance()))
            pushHandler(ProtobufEncoder())
            debugHandler?.also { pushHandler(it) }
            pushHandler(streamHandler)
        }
    }

    override fun removePeer(peer: Stream) {
        peer.close()
    }

    /**
     * Broadcasts to peers validated unseen messages received from api
     */
    protected abstract fun broadcastOutbound(msg: Rpc.Message): CompletableFuture<Unit>

    /**
     * Broadcasts to peers validated unseen messages received from another peer
     */
    protected abstract fun broadcastInbound(msg: Rpc.RPC, receivedFrom: PeerHandler)

    /**
     * Processes Pubsub control message
     */
    protected abstract fun processControl(ctrl: Rpc.ControlMessage, receivedFrom: PeerHandler)

    override fun onPeerActive(peer: PeerHandler) {
        val helloPubsubMsg = Rpc.RPC.newBuilder().addAllSubscriptions(subscribedTopics.map {
            Rpc.RPC.SubOpts.newBuilder().setSubscribe(true).setTopicid(it).build()
        }).build()

        peer.writeAndFlush(helloPubsubMsg)
    }

    override fun onInbound(peer: PeerHandler, msg: Any) {
        msg as Rpc.RPC
        msg.subscriptionsList.forEach { handleMessageSubscriptions(peer, it) }
        if (msg.hasControl()) {
            processControl(msg.control, peer)
        }
        val msgUnseen = filterSeen(msg)
        if (msgUnseen.publishCount > 0) {
            validator.validate(msgUnseen)
            msgUnseen.publishList.forEach(msgHandler)
            seenMessages += msg.publishList.map { getMessageId(it) }
            broadcastInbound(msgUnseen, peer)
        }
        flushAllPending()
    }

    override fun onPeerDisconnected(peer: PeerHandler) {
        peerTopics.removeAll(peer)
    }

    private fun handleMessageSubscriptions(peer: PeerHandler, msg: Rpc.RPC.SubOpts) {
        if (msg.subscribe) {
            peerTopics[peer] += msg.topicid
        } else {
            peerTopics[peer] -= msg.topicid
        }
    }

    protected fun getTopicsPeers(topics: Collection<String>) =
        activePeers.filter { topics.intersect(peerTopics[it]).isNotEmpty() }
    protected fun getTopicPeers(topic: String) =
        activePeers.filter { topic in peerTopics[it] }

    private fun filterSeen(msg: Rpc.RPC): Rpc.RPC =
        Rpc.RPC.newBuilder(msg)
            .clearPublish()
            .addAllPublish(msg.publishList.filter { getMessageId(it) !in seenMessages })
            .build()

    override fun subscribe(vararg topics: String) {
        runOnEventThread {
            topics.forEach(::subscribe)
            flushAllPending()
        }
    }

    protected open fun subscribe(topic: String) {
        activePeers.forEach { addPendingRpcPart(it,
                Rpc.RPC.newBuilder().addSubscriptions(Rpc.RPC.SubOpts.newBuilder().setSubscribe(true).setTopicid(topic)).build()
            ) }
        subscribedTopics += topic
    }

    override fun unsubscribe(vararg topics: String) {
        runOnEventThread {
            topics.forEach(::unsubscribe)
            flushAllPending()
        }
    }

    protected open fun unsubscribe(topic: String) {
        activePeers.forEach { addPendingRpcPart(it,
            Rpc.RPC.newBuilder().addSubscriptions(Rpc.RPC.SubOpts.newBuilder().setSubscribe(false).setTopicid(topic)).build()
        ) }
        subscribedTopics -= topic
    }

    private fun send(peer: PeerHandler, msg: Rpc.RPC): CompletableFuture<Unit> {
        return peer.writeAndFlush(msg)
    }

    override fun initHandler(handler: (Rpc.Message) -> Unit) {
        msgHandler = handler
    }
}