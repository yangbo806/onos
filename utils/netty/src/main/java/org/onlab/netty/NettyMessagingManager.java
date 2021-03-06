/*
 * Copyright 2014-2015 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onlab.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.commons.pool.KeyedPoolableObjectFactory;
import org.apache.commons.pool.impl.GenericKeyedObjectPool;
import org.onlab.packet.IpAddress;
import org.onosproject.store.cluster.messaging.Endpoint;
import org.onosproject.store.cluster.messaging.MessagingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

/**
 * Implementation of MessagingService based on <a href="http://netty.io/">Netty</a> framework.
 */
public class NettyMessagingManager implements MessagingService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private static final String REPLY_MESSAGE_TYPE = "NETTY_MESSAGING_REQUEST_REPLY";

    private final Endpoint localEp;
    private final ConcurrentMap<String, Consumer<InternalMessage>> handlers = new ConcurrentHashMap<>();
    private final AtomicLong messageIdGenerator = new AtomicLong(0);
    private final Cache<Long, CompletableFuture<byte[]>> responseFutures = CacheBuilder.newBuilder()
            .expireAfterWrite(10, TimeUnit.SECONDS)
            .removalListener(new RemovalListener<Long, CompletableFuture<byte[]>>() {
                @Override
                public void onRemoval(RemovalNotification<Long, CompletableFuture<byte[]>> entry) {
                    if (entry.wasEvicted()) {
                        entry.getValue().completeExceptionally(new TimeoutException("Timedout waiting for reply"));
                    }
                }
            })
            .build();

    private final GenericKeyedObjectPool<Endpoint, Channel> channels
            = new GenericKeyedObjectPool<Endpoint, Channel>(new OnosCommunicationChannelFactory());

    private EventLoopGroup serverGroup;
    private EventLoopGroup clientGroup;
    private Class<? extends ServerChannel> serverChannelClass;
    private Class<? extends Channel> clientChannelClass;

    private void initEventLoopGroup() {
        // try Epoll first and if that does work, use nio.
        try {
            clientGroup = new EpollEventLoopGroup();
            serverGroup = new EpollEventLoopGroup();
            serverChannelClass = EpollServerSocketChannel.class;
            clientChannelClass = EpollSocketChannel.class;
            return;
        } catch (Throwable e) {
            log.warn("Failed to initialize native (epoll) transport. Reason: {}. Proceeding with nio.", e.getMessage());
        }
        clientGroup = new NioEventLoopGroup();
        serverGroup = new NioEventLoopGroup();
        serverChannelClass = NioServerSocketChannel.class;
        clientChannelClass = NioSocketChannel.class;
    }

    public NettyMessagingManager(IpAddress ip, int port) {
        localEp = new Endpoint(ip, port);
    }

    public NettyMessagingManager() {
        this(8080);
    }

    public NettyMessagingManager(int port) {
        try {
            localEp = new Endpoint(IpAddress.valueOf(InetAddress.getLocalHost()), port);
        } catch (UnknownHostException e) {
            // Cannot resolve the local host, something is very wrong. Bailing out.
            throw new IllegalStateException("Cannot resolve local host", e);
        }
    }

    public void activate() throws InterruptedException {
        channels.setLifo(false);
        channels.setTestOnBorrow(true);
        channels.setTestOnReturn(true);
        initEventLoopGroup();
        startAcceptingConnections();
    }

    public void deactivate() throws Exception {
        channels.close();
        serverGroup.shutdownGracefully();
        clientGroup.shutdownGracefully();
    }

    /**
     * Returns the local endpoint for this instance.
     * @return local end point.
     */
    public Endpoint localEp() {
        return localEp;
    }

    @Override
    public void sendAsync(Endpoint ep, String type, byte[] payload) throws IOException {
        InternalMessage message = new InternalMessage(messageIdGenerator.incrementAndGet(),
                                                      localEp,
                                                      type,
                                                      payload);
        sendAsync(ep, message);
    }

    protected void sendAsync(Endpoint ep, InternalMessage message) throws IOException {
        if (ep.equals(localEp)) {
            dispatchLocally(message);
            return;
        }
        Channel channel = null;
        try {
            try {
                channel = channels.borrowObject(ep);
                channel.writeAndFlush(message).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            } finally {
                channels.returnObject(ep, channel);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public CompletableFuture<byte[]> sendAndReceive(Endpoint ep, String type, byte[] payload) {
        CompletableFuture<byte[]> response = new CompletableFuture<>();
        Long messageId = messageIdGenerator.incrementAndGet();
        responseFutures.put(messageId, response);
        InternalMessage message = new InternalMessage(messageId, localEp, type, payload);
        try {
            sendAsync(ep, message);
        } catch (Exception e) {
            responseFutures.invalidate(messageId);
            response.completeExceptionally(e);
        }
        return response;
    }

    @Override
    public void registerHandler(String type, Consumer<byte[]> handler, Executor executor) {
        handlers.put(type, message -> executor.execute(() -> handler.accept(message.payload())));
    }

    @Override
    public void registerHandler(String type, Function<byte[], byte[]> handler, Executor executor) {
        handlers.put(type, message -> executor.execute(() -> {
            byte[] responsePayload = handler.apply(message.payload());
            if (responsePayload != null) {
                InternalMessage response = new InternalMessage(message.id(),
                        localEp,
                        REPLY_MESSAGE_TYPE,
                        responsePayload);
                try {
                    sendAsync(message.sender(), response);
                } catch (IOException e) {
                    log.debug("Failed to respond", e);
                }
            }
        }));
    }

    @Override
    public void unregisterHandler(String type) {
        handlers.remove(type);
    }

    private void startAcceptingConnections() throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 32 * 1024);
        b.option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 8 * 1024);
        b.option(ChannelOption.SO_RCVBUF, 1048576);
        b.option(ChannelOption.TCP_NODELAY, true);
        b.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        b.group(serverGroup, clientGroup)
            .channel(serverChannelClass)
            .childHandler(new OnosCommunicationChannelInitializer())
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, true);

        // Bind and start to accept incoming connections.
        b.bind(localEp.port()).sync();
    }

    private class OnosCommunicationChannelFactory
        implements KeyedPoolableObjectFactory<Endpoint, Channel> {

        @Override
        public void activateObject(Endpoint endpoint, Channel channel)
                throws Exception {
        }

        @Override
        public void destroyObject(Endpoint ep, Channel channel) throws Exception {
            channel.close();
        }

        @Override
        public Channel makeObject(Endpoint ep) throws Exception {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
            bootstrap.option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 10 * 64 * 1024);
            bootstrap.option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 10 * 32 * 1024);
            bootstrap.option(ChannelOption.SO_SNDBUF, 1048576);
            bootstrap.group(clientGroup);
            // TODO: Make this faster:
            // http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#37.0
            bootstrap.channel(clientChannelClass);
            bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
            bootstrap.handler(new OnosCommunicationChannelInitializer());
            // Start the client.
            ChannelFuture f = bootstrap.connect(ep.host().toString(), ep.port()).sync();
            log.info("Established a new connection to {}", ep);
            return f.channel();
        }

        @Override
        public void passivateObject(Endpoint ep, Channel channel)
                throws Exception {
        }

        @Override
        public boolean validateObject(Endpoint ep, Channel channel) {
            return channel.isOpen();
        }
    }

    private class OnosCommunicationChannelInitializer extends ChannelInitializer<SocketChannel> {

        private final ChannelHandler dispatcher = new InboundMessageDispatcher();
        private final ChannelHandler encoder = new MessageEncoder();

        @Override
        protected void initChannel(SocketChannel channel) throws Exception {
            channel.pipeline()
                .addLast("encoder", encoder)
                .addLast("decoder", new MessageDecoder())
                .addLast("handler", dispatcher);
        }
    }

    @ChannelHandler.Sharable
    private class InboundMessageDispatcher extends SimpleChannelInboundHandler<InternalMessage> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, InternalMessage message) throws Exception {
            dispatchLocally(message);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            log.error("Exception inside channel handling pipeline.", cause);
            context.close();
        }
    }

    private void dispatchLocally(InternalMessage message) throws IOException {
        String type = message.type();
        if (REPLY_MESSAGE_TYPE.equals(type)) {
            try {
                CompletableFuture<byte[]> futureResponse =
                    responseFutures.getIfPresent(message.id());
                if (futureResponse != null) {
                    futureResponse.complete(message.payload());
                } else {
                    log.warn("Received a reply for message id:[{}]. "
                            + " from {}. But was unable to locate the"
                            + " request handle", message.id(), message.sender());
                }
            } finally {
                responseFutures.invalidate(message.id());
            }
            return;
        }
        Consumer<InternalMessage> handler = handlers.get(type);
        if (handler != null) {
            handler.accept(message);
        } else {
            log.debug("No handler registered for {}", type);
        }
    }
}
