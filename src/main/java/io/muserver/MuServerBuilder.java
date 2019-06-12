package io.muserver;

import io.muserver.handlers.ResourceHandler;
import io.muserver.handlers.ResourceType;
import io.muserver.rest.MuRuntimeDelegate;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>A builder for creating a web server.</p>
 * <p>Use the <code>withXXX()</code> methods to set the ports, config, and request handlers needed.</p>
 */
public class MuServerBuilder {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    private static final Logger log = LoggerFactory.getLogger(MuServerBuilder.class);
    private static final int LENGTH_OF_METHOD_AND_PROTOCOL = 17; // e.g. "OPTIONS HTTP/1.1 "
    private long minimumGzipSize = 1400;
    private int httpPort = -1;
    private int httpsPort = -1;
    private int maxHeadersSize = 8192;
    private int maxUrlSize = 8192 - LENGTH_OF_METHOD_AND_PROTOCOL;
    private List<MuHandler> handlers = new ArrayList<>();
    private boolean gzipEnabled = true;
    private Set<String> mimeTypesToGzip = ResourceType.gzippableMimeTypes(ResourceType.getResourceTypes());
    private boolean addShutdownHook = false;
    private String host;
    private SSLContextBuilder sslContextBuilder;
    private Http2Config http2Config;
    private long idleTimeoutMills = TimeUnit.MINUTES.toMillis(5);
    private ExecutorService executor;

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
     * Use this to specify which network interface to bind to.
     * @param host The host to bind to, for example <code>"127.0.0.1"</code> to restrict connections from localhost
     *             only, or <code>"0.0.0.0"</code> to allow connections from the local network.
     * @return The current Mu-Server Builder
     */
    public MuServerBuilder withInterface(String host) {
        this.host = host;
        return this;
    }

    /**
     * @param stopServerOnShutdown If true, then a shutdown hook which stops this server will be added to the JVM Runtime
     * @return The current Mu-Server Builder
     */
    public MuServerBuilder addShutdownHook(boolean stopServerOnShutdown) {
        this.addShutdownHook = stopServerOnShutdown;
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

    /**
     * Enables gzip for certain resource types. The default is <code>true</code>. By default, the
     * gzippable resource types are taken from {@link ResourceType#getResourceTypes()} where
     * {@link ResourceType#gzip} is <code>true</code>.
     * @see #withGzip(long, Set)
     * @param enabled True to enable; false to disable
     * @return The current Mu-Server builder
     */
    public MuServerBuilder withGzipEnabled(boolean enabled) {
        this.gzipEnabled = enabled;
        return this;
    }

    /**
     * Enables gzip for files of at least the specified size that match the given mime-types.
     * By default, gzip is enabled for text-based mime types over 1400 bytes. It is recommended
     * to keep the defaults and only use this method if you have very specific requirements
     * around GZIP.
     * @param minimumGzipSize The size in bytes before gzip is used. The default is 1400.
     * @param mimeTypesToGzip The mime-types that should be gzipped. In general, only text
     *                        files should be gzipped.
     * @return The current Mu-Server Builder
     */
    public MuServerBuilder withGzip(long minimumGzipSize, Set<String> mimeTypesToGzip) {
        this.gzipEnabled = true;
        this.mimeTypesToGzip = mimeTypesToGzip;
        this.minimumGzipSize = minimumGzipSize;
        return this;
    }

    /**
     * Turns off HTTP.
     * @return The current builder.
     * @deprecated It is off by default so this is not needed.
     */
    @Deprecated
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
        return withHttpsConfig(SSLContextBuilder.sslContext().withSSLContext(sslContext));
    }

    /**
     * Sets the HTTPS config. Defaults to {@link SSLContextBuilder#unsignedLocalhostCert()}
     * @see SSLContextBuilder
     * @param sslContext An SSL Context builder.
     * @return The current Mu-Server Builder
     */
    public MuServerBuilder withHttpsConfig(SSLContextBuilder sslContext) {
        this.sslContextBuilder = sslContext;
        return this;
    }

    /**
     * Sets the HTTPS port to use. To set the SSL certificate config, see {@link #withHttpsConfig(SSLContextBuilder)}
     * @param port A value of 0 will result in a random port being assigned; a value of -1 will
     * disable HTTPS.
     * @return The current Mu-Server builder
     */
    public MuServerBuilder withHttpsPort(int port) {
        this.httpsPort = port;
        return this;
    }

    /**
     * Sets the configuration for HTTP2
     * @param http2Config A config
     * @return The current Mu-Server builder
     * @see Http2ConfigBuilder
     */
    public MuServerBuilder withHttp2Config(Http2Config http2Config) {
        this.http2Config = http2Config;
        return this;
    }

    /**
     * Sets the configuration for HTTP2
     * @param http2Config A config
     * @return The current Mu-Server builder
     * @see Http2ConfigBuilder
     */
    public MuServerBuilder withHttp2Config(Http2ConfigBuilder http2Config) {
        return withHttp2Config(http2Config.build());
    }

    /**
     * Sets the thread executor service to run requests on. By default {@link Executors#newCachedThreadPool()}
     * is used.
     * @param executor The executor service to use to handle requests
     * @return The current Mu-Server builder
     */
    public MuServerBuilder withHandlerExecutor(ExecutorService executor) {
        this.executor = executor;
        return this;
    }

    /**
     * <p>Specifies the maximum size in bytes of the HTTP request headers. Defaults to 8192.</p>
     * <p>If a request has headers exceeding this value, it will be rejected and a <code>431</code>
     * status code will be returned. Large values increase the risk of Denial-of-Service attacks
     * due to the extra memory allocated in each request.</p>
     * <p>It is recommended to not specify a value unless you are finding legitimate requests are
     * being rejected with <code>413</code> errors.</p>
     * @param size The maximum size in bytes that can be used for headers.
     * @return The current Mu-Server builder.
     */
    public MuServerBuilder withMaxHeadersSize(int size) {
        this.maxHeadersSize = size;
        return this;
    }

    /**
     * The maximum length that a URL can be. If it exceeds this value, a <code>414</code> error is
     * returned to the client. The default value is 8175.
     * @param size The maximum number of characters allowed in URLs sent to this server.
     * @return The current Mu-Server builder
     */
    public MuServerBuilder withMaxUrlSize(int size) {
        this.maxUrlSize = size;
        return this;
    }

    /**
     * Sets the idle timeout for requests and responses. If no bytes are sent or received within this time then
     * the connection is closed.
     * <p>The default is 5 minutes.</p>
     * @param duration The allowed timeout duration, or 0 to disable timeouts.
     * @param unit The unit of the duration.
     * @return This builder
     */
    public MuServerBuilder withIdleTimeout(long duration, TimeUnit unit) {
        if (duration < 0) {
            throw new IllegalArgumentException("The duration must be 0 or greater");
        }
        Mutils.notNull("unit", unit);
        this.idleTimeoutMills = unit.toMillis(duration);
        return this;
    }

    /**
     * <p>Throws an exception. Do not use.</p>
     * @param handler Ignored
     * @deprecated For async handling, add a normal {@link MuHandler} and call {@link MuRequest#handleAsync()}
     * @return Never returns
     */
    @Deprecated
    public MuServerBuilder addAsyncHandler(AsyncMuHandler handler) {
        throw new RuntimeException("This method is not supported. For async handling, add a normal MuHandler and call MuRequest#handleAsync()");
    }

    /**
     * <p>Adds a request handler.</p>
     * <p>Note that handlers are executed in the order added to the builder, but all async
     * handlers are executed before synchronous handlers.</p>
     * @see #addHandler(Method, String, RouteHandler)
     * @param handler A handler builder. The <code>build()</code> method will be called on this
     *                to create the handler. If null, then no handler is added.
     * @return The current Mu-Server Handler.
     */
    public MuServerBuilder addHandler(MuHandlerBuilder handler) {
        if (handler == null) {
            return this;
        }
        return addHandler(handler.build());
    }

    /**
     * <p>Adds a request handler.</p>
     * <p>Note that handlers are executed in the order added to the builder, but all async
     * handlers are executed before synchronous handlers.</p>
     * @see #addHandler(Method, String, RouteHandler)
     * @param handler The handler to add. If null, then no handler is added.
     * @return The current Mu-Server Handler.
     */
    public MuServerBuilder addHandler(MuHandler handler) {
        if (handler != null) {
            handler = getContextualHandlerForResourceHandler(handler);
            handlers.add(handler);
        }
        return this;
    }

    static MuHandler getContextualHandlerForResourceHandler(MuHandler handler) {
        // Temporary workaround until the path-to-serve-from is demised
        if (handler instanceof ResourceHandler) {
            ResourceHandler rh = (ResourceHandler) handler;
            String context = Mutils.trim(Mutils.coalesce(rh.getPathToServeFrom(), ""), "/");
            if (!Mutils.nullOrEmpty(context)) {
                handler = ContextHandlerBuilder.context(context)
                    .addHandlerTemp(rh)
                    .build();
            }
        }
        return handler;
    }

    /**
     * Registers a new handler that will only be called if it matches the given route info
     * @param method The method to match, or <code>null</code> to accept any method.
     * @param uriTemplate A URL template. Supports plain URLs like <code>/abc</code> or paths
     *                   with named parameters such as <code>/abc/{id}</code> or named parameters
     *                    with regexes such as <code>/abc/{id : [0-9]+}</code> where the named
     *                    parameter values can be accessed with the <code>pathParams</code>
     *                    parameter in the route handler.
     * @param handler The handler to invoke if the method and URI matches. If null, then no handler is added.
     * @return Returns the server builder
     */
    public MuServerBuilder addHandler(Method method, String uriTemplate, RouteHandler handler) {
        if (handler == null) {
            return this;
        }
        return addHandler(Routes.route(method, uriTemplate, handler));
    }

    static class ServerSettings {
        final long minimumGzipSize;
        final int maxHeadersSize;
        final int maxUrlSize;
        final boolean gzipEnabled;
        final Set<String> mimeTypesToGzip;

        ServerSettings(long minimumGzipSize, int maxHeadersSize, int maxUrlSize, boolean gzipEnabled, Set<String> mimeTypesToGzip) {
            this.minimumGzipSize = minimumGzipSize;
            this.maxHeadersSize = maxHeadersSize;
            this.maxUrlSize = maxUrlSize;
            this.gzipEnabled = gzipEnabled;
            this.mimeTypesToGzip = mimeTypesToGzip;
        }

        boolean shouldCompress(String declaredLength, String contentType) {
            if (!gzipEnabled) {
                return false;
            }
            if (declaredLength != null && Long.parseLong(declaredLength) <= minimumGzipSize) {
                return false;
            }
            if (contentType == null) {
                return false;
            }
            int i = contentType.indexOf(";");
            if (i > -1) {
                contentType = contentType.substring(0, i);
            }
            return mimeTypesToGzip.contains(contentType.trim());
        }
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


    /**
     * Creates and starts this server. An exception is thrown if it fails to start.
     * @return The running server.
     */
    public MuServer start() {
        if (httpPort < 0 && httpsPort < 0) {
            throw new IllegalArgumentException("No ports were configured. Please call MuServerBuilder.withHttpPort(int) or MuServerBuilder.withHttpsPort(int)");
        }

        ServerSettings settings = new ServerSettings(minimumGzipSize, maxHeadersSize, maxUrlSize, gzipEnabled, mimeTypesToGzip);

        ExecutorService handlerExecutor = this.executor;
        if (handlerExecutor == null) {
            handlerExecutor = Executors.newCachedThreadPool(new DefaultThreadFactory("muhandler"));
        }
        NettyHandlerAdapter nettyHandlerAdapter = new NettyHandlerAdapter(handlerExecutor, handlers);

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
                log.info("Error while shutting down. Will ignore. Error was: " + e.getMessage());
            }
        };

        try {
            GlobalTrafficShapingHandler trafficShapingHandler = new GlobalTrafficShapingHandler(workerGroup, 0, 0, 1000);
            MuStatsImpl stats = new MuStatsImpl(trafficShapingHandler.trafficCounter());
            AtomicReference<MuServer> serverRef = new AtomicReference<>();
            SslContextProvider sslContextProvider = null;

            Channel httpChannel = httpPort < 0 ? null : createChannel(bossGroup, workerGroup, nettyHandlerAdapter, host, httpPort, null, trafficShapingHandler, stats, serverRef, settings, false, idleTimeoutMills);
            Channel httpsChannel;
            boolean http2Enabled = http2Config != null && http2Config.enabled;
            if (httpsPort < 0) {
                httpsChannel = null;
            } else {
                SSLContextBuilder toUse = this.sslContextBuilder != null ? this.sslContextBuilder : SSLContextBuilder.unsignedLocalhostCertBuilder();
                SslContext nettySslContext = toUse.toNettySslContext(http2Enabled);
                log.debug("SSL Context is " + nettySslContext);
                sslContextProvider = new SslContextProvider(nettySslContext);
                httpsChannel = createChannel(bossGroup, workerGroup, nettyHandlerAdapter, host, httpsPort, sslContextProvider, trafficShapingHandler, stats, serverRef, settings, http2Enabled, idleTimeoutMills);
            }
            URI uri = null;
            if (httpChannel != null) {
                channels.add(httpChannel);
                uri = getUriFromChannel(httpChannel, "http", host);
            }
            URI httpsUri = null;
            if (httpsChannel != null) {
                channels.add(httpsChannel);
                httpsUri = getUriFromChannel(httpsChannel, "https", host);
                ((SSLInfoImpl)sslContextProvider.sslInfo()).setHttpsUri(httpsUri);
            }

            InetSocketAddress serverAddress = (InetSocketAddress) channels.get(0).localAddress();
            MuServer server = new MuServerImpl(uri, httpsUri, shutdown, stats, serverAddress, sslContextProvider, http2Enabled);
            serverRef.set(server);
            if (addShutdownHook) {
                Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            }
            return server;

        } catch (Exception ex) {
            shutdown.run();
            throw new MuException("Error while starting server", ex);
        }

    }

    private static URI getUriFromChannel(Channel httpChannel, String protocol, String host) {
        host = host == null ? "localhost" : host;
        InetSocketAddress a = (InetSocketAddress) httpChannel.localAddress();
        return URI.create(protocol + "://" + host.toLowerCase() + ":" + a.getPort());
    }

    private static Channel createChannel(NioEventLoopGroup bossGroup, NioEventLoopGroup workerGroup, NettyHandlerAdapter nettyHandlerAdapter, String host, int port, SslContextProvider sslContextProvider, GlobalTrafficShapingHandler trafficShapingHandler, MuStatsImpl stats, AtomicReference<MuServer> serverRef, ServerSettings settings, final boolean http2, long idleTimeoutMills) throws InterruptedException {
        boolean usesSsl = sslContextProvider != null;
        String proto = usesSsl ? "https" : "http";
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {

                protected void initChannel(SocketChannel socketChannel) {
                    ChannelPipeline p = socketChannel.pipeline();
                    p.addLast("idle", new IdleStateHandler(0, 0, idleTimeoutMills, TimeUnit.MILLISECONDS));
                    p.addLast(trafficShapingHandler);
                    if (usesSsl) {
                        SslHandler sslHandler = sslContextProvider.get().newHandler(socketChannel.alloc());
                        SSLParameters params = sslHandler.engine().getSSLParameters();
                        params.setUseCipherSuitesOrder(true);
                        sslHandler.engine().setSSLParameters(params);
                        p.addLast("ssl", sslHandler);
                    }
                    if (http2 && usesSsl) {
                        p.addLast("http1or2", new AlpnHandler(nettyHandlerAdapter, stats, serverRef, proto, settings));
                    } else {
                        setupHttp1Pipeline(p, settings, nettyHandlerAdapter, stats, serverRef, proto);
                    }
                }


            });
        ChannelFuture bound = host == null ? b.bind(port) : b.bind(host, port);
        return bound.sync().channel();
    }
    static void setupHttp1Pipeline(ChannelPipeline p, ServerSettings settings, NettyHandlerAdapter nettyHandlerAdapter, MuStatsImpl stats, AtomicReference<MuServer> serverRef, String proto) {
        p.addLast("decoder", new HttpRequestDecoder(settings.maxUrlSize + LENGTH_OF_METHOD_AND_PROTOCOL, settings.maxHeadersSize, 8192));
        p.addLast("encoder", new HttpResponseEncoder() {
            @Override
            protected boolean isContentAlwaysEmpty(HttpResponse msg) {
                return super.isContentAlwaysEmpty(msg) || msg instanceof NettyResponseAdaptor.EmptyHttpResponse;
            }
        });
        if (settings.gzipEnabled) {
            p.addLast("compressor", new SelectiveHttpContentCompressor(settings));
        }
        p.addLast("keepalive", new HttpServerKeepAliveHandler());
        p.addLast("muhandler", new Http1Connection(nettyHandlerAdapter, stats, serverRef, proto));
    }



}
