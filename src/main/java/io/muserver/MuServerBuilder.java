package io.muserver;

import io.muserver.handlers.ResourceType;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.util.Attribute;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerHandler.PROTO_ATTRIBUTE;

public class MuServerBuilder {
    private static final int LENGTH_OF_METHOD_AND_PROTOCOL = 17; // e.g. "OPTIONS HTTP/1.1 "
    private long minimumGzipSize = 1400;
    private int httpPort = -1;
    private int httpsPort = -1;
    private int maxHeadersSize = 8192;
    private int maxUrlSize = 8192 - LENGTH_OF_METHOD_AND_PROTOCOL;
    private List<AsyncMuHandler> asyncHandlers = new ArrayList<>();
    private List<MuHandler> handlers = new ArrayList<>();
    private SSLContext sslContext;
    private boolean gzipEnabled = true;
    private Set<String> mimeTypesToGzip = ResourceType.gzippableMimeTypes(ResourceType.getResourceTypes());

    /**
     * @param port The HTTP port to use. A value of 0 will have a random port assigned; a value of -1 will
     *             result in no HTTP connector.
     * @return The current Mu-Server Builder
     */
    public MuServerBuilder withHttpPort(int port) {
        this.httpPort = port;
        return this;
    }

    /**
     * @param port The HTTP port to use. A value of 0 will have a random port assigned; a value of -1 will
     *             result in no HTTP connector.
     * @return The current Mu-Server Builder
     * @deprecated Use {@link #withHttpPort(int)} instead
     */
    @Deprecated
    public MuServerBuilder withHttpConnection(int port) {
        return withHttpPort(port);
    }

    public MuServerBuilder withGzipEnabled(boolean enabled) {
        this.gzipEnabled = enabled;
        return this;
    }
    public MuServerBuilder withGzip(long minimumGzipSize, Set<String> mimeTypesToGzip) {
        this.gzipEnabled = true;
        this.mimeTypesToGzip = mimeTypesToGzip;
        this.minimumGzipSize = minimumGzipSize;
        return this;
    }

    public MuServerBuilder withHttpDisabled() {
        this.httpPort = -1;
        return this;
    }

    /**
     * @param port The port
     * @param sslEngine The SSL Context
     * @return The builder
     * @deprecated use {@link #withHttpsPort(int)} and {@link #withHttpsConfig(SSLContext)} instead.
     */
    @Deprecated
    public MuServerBuilder withHttpsConnection(int port, SSLContext sslEngine) {
        return withHttpsPort(port).withHttpsConfig(sslEngine);
    }

    /**
     * Sets the HTTPS config. Defaults to {@link SSLContextBuilder#unsignedLocalhostCert()}
     * @see SSLContextBuilder
     * @param sslContext An SSL Context.
     * @return The current Mu-Server Builder
     */
    public MuServerBuilder withHttpsConfig(SSLContext sslContext) {
        this.sslContext = sslContext;
        return this;
    }

    /**
     * Sets the HTTPS config. Defaults to {@link SSLContextBuilder#unsignedLocalhostCert()}
     * @see SSLContextBuilder
     * @param sslContext An SSL Context builder.
     * @return The current Mu-Server Builder
     */
    public MuServerBuilder withHttpsConfig(SSLContextBuilder sslContext) {
        return withHttpsConfig(sslContext.build());
    }

    /**
     * Sets the HTTPS port to use. To set the SSL certificate config, see {@link }
     * @param port A value of 0 will result in a random port being assigned; a value of -1 will
     * disable HTTPS.
     * @return The current Mu-Server builder
     */
    public MuServerBuilder withHttpsPort(int port) {
        this.httpsPort = port;
        return this;
    }


    public MuServerBuilder withHttpsDisabled() {
        this.httpsPort = -1;
        this.sslContext = null;
        return this;
    }

    public MuServerBuilder withMaxHeadersSize(int size) {
        this.maxHeadersSize = size;
        return this;
    }

    public MuServerBuilder withMaxUrlSize(int size) {
        this.maxUrlSize = size;
        return this;
    }

    /**
     * @param handler An Async Handler
     * @return The builder
     */
    public MuServerBuilder addAsyncHandler(AsyncMuHandler handler) {
        asyncHandlers.add(handler);
        return this;
    }

    public MuServerBuilder addHandler(MuHandlerBuilder handler) {
        return addHandler(handler.build());
    }
    public MuServerBuilder addHandler(MuHandler handler) {
        handlers.add(handler);
        return this;
    }

    /**
     * Registers a new handler that will only be called if it matches the given route info
     * @param method The method to match, or <code>null</code> to accept any method.
     * @param uriTemplate A URL template. Supports plain URLs like <code>/abc</code> or paths
     *                   with named parameters such as <code>/abc/{id}</code> or named parameters
     *                    with regexes such as <code>/abc/{id : [0-9]+}</code> where the named
     *                    parameter values can be accessed with the <code>pathParams</code>
     *                    parameter in the route handler.
     * @param handler The handler to invoke if the method and URI matches.
     * @return Returns the server builder
     */
    public MuServerBuilder addHandler(Method method, String uriTemplate, RouteHandler handler) {
        return addHandler(Routes.route(method, uriTemplate, handler));
    }


    public MuServer start() {
        if (!handlers.isEmpty()) {
            asyncHandlers.add(new SyncHandlerAdapter(handlers));
        }
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup workerGroup = new NioEventLoopGroup();
        List<Channel> channels = new ArrayList<>();

        Runnable shutdown = () -> {
            try {
                for (Channel channel : channels) {
                    channel.close().sync();
                }
                bossGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
                workerGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();

            } catch (Exception e) {
                System.out.println("Error while shutting down. Will ignore. Error was: " + e.getMessage());
            }
        };


        try {
            Channel httpChannel = httpPort < 0 ? null : createChannel(bossGroup, workerGroup, httpPort, null);
            Channel httpsChannel;
            if (httpsPort < 0) {
                httpsChannel = null;
            } else {
                SSLContext sslContextToUse = this.sslContext != null ? this.sslContext : SSLContextBuilder.unsignedLocalhostCert();
                httpsChannel = createChannel(bossGroup, workerGroup, httpsPort, sslContextToUse);
            }
            URI uri = null;
            if (httpChannel != null) {
                channels.add(httpChannel);
                uri = getUriFromChannel(httpChannel, "http");
            }
            URI httpsUri = null;
            if (httpsChannel != null) {
                channels.add(httpsChannel);
                httpsUri = getUriFromChannel(httpsChannel, "https");
            }

            return new MuServer(uri, httpsUri, shutdown);

        } catch (Exception ex) {
            shutdown.run();
            throw new MuException("Error while starting server", ex);
        }

    }

    private static URI getUriFromChannel(Channel httpChannel, String protocol) {
        InetSocketAddress a = (InetSocketAddress) httpChannel.localAddress();
        return URI.create(protocol + "://localhost:" + a.getPort());
    }

    private Channel createChannel(NioEventLoopGroup bossGroup, NioEventLoopGroup workerGroup, int port, SSLContext rawSSLContext) throws InterruptedException {
        boolean usesSsl = rawSSLContext != null;
        JdkSslContext sslContext = usesSsl ? new JdkSslContext(rawSSLContext, false, ClientAuth.NONE) : null;

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {

                protected void initChannel(SocketChannel socketChannel) throws Exception {
                    Attribute<String> proto = socketChannel.attr(PROTO_ATTRIBUTE);
                    proto.set(usesSsl ? "https" : "http");
                    ChannelPipeline p = socketChannel.pipeline();
                    if (usesSsl) {
                        p.addLast("ssl", sslContext.newHandler(socketChannel.alloc()));
                    }
                    p.addLast("decoder", new HttpRequestDecoder(maxUrlSize + LENGTH_OF_METHOD_AND_PROTOCOL, maxHeadersSize, 8192));
                    p.addLast("encoder", new HttpResponseEncoder() {
                        @Override
                        protected boolean isContentAlwaysEmpty(HttpResponse msg) {
                            return super.isContentAlwaysEmpty(msg) || msg instanceof NettyResponseAdaptor.EmptyHttpResponse;
                        }
                    });
                    if (gzipEnabled) {
                        p.addLast("compressor", new SelectiveHttpContentCompressor(minimumGzipSize, mimeTypesToGzip));
                    }
                    p.addLast("muhandler", new MuServerHandler(asyncHandlers));
                }
            });
        return b.bind(port).sync().channel();
    }

    /**
     * Creates a new server builder. Call {@link #withHttpsPort(int)} or {@link #withHttpPort(int)} to specify
     * the port to use, and call {@link #start()} to start the server.
     * @return A new Mu-Server builder
     */
    public static MuServerBuilder muServer() {
        return new MuServerBuilder();
    }

    /**
     * Creates a new server builder which will run as HTTP on a random port.
     * @return A new Mu-Server builder with the HTTP port set to 0
     */
    public static MuServerBuilder httpServer() {
        return muServer().withHttpPort(0);
    }

    /**
     * Creates a new server builder which will run as HTTPS on a random port.
     * @return A new Mu-Server builder with the HTTPS port set to 0
     */
    public static MuServerBuilder httpsServer() {
        return muServer().withHttpsPort(0);
    }
}
