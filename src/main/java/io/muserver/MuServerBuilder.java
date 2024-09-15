package io.muserver;

import io.muserver.handlers.ResourceType;
import io.muserver.rest.MuRuntimeDelegate;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.HashedWheelTimer;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLParameters;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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
    private static final int DEFAULT_NIO_THREADS = Math.min(16, Runtime.getRuntime().availableProcessors() * 2);
    private long minimumGzipSize = 1400;
    private int httpPort = -1;
    private int httpsPort = -1;
    private int maxHeadersSize = 8192;
    private int maxUrlSize = 8192 - LENGTH_OF_METHOD_AND_PROTOCOL;
    private int nioThreads = DEFAULT_NIO_THREADS;
    private final List<MuHandler> handlers = new ArrayList<>();
    private boolean gzipEnabled = true;
    private Set<String> mimeTypesToGzip = ResourceType.gzippableMimeTypes(ResourceType.getResourceTypes());
    private boolean addShutdownHook = false;
    private String host;
    private HttpsConfigBuilder sslContextBuilder;
    private Http2Config http2Config;
    private long requestReadTimeoutMillis = TimeUnit.MINUTES.toMillis(2);
    private long idleTimeoutMills = TimeUnit.MINUTES.toMillis(10);
    private ExecutorService executor;
    private long maxRequestSize = 24 * 1024 * 1024;
    private List<ResponseCompleteListener> responseCompleteListeners;
    private HashedWheelTimer wheelTimer;
    private List<RateLimiterImpl> rateLimiters;
    private WriteBufferWaterMark writeBufferWaterMark = WriteBufferWaterMark.DEFAULT;
    private UnhandledExceptionHandler unhandledExceptionHandler;

    /**
     * @param port The HTTP port to use. A value of 0 will have a random port assigned; a value of -1 will
     *             result in no HTTP connector.
     * @return The current Mu Server Builder
     */
    public MuServerBuilder withHttpPort(int port) {
        this.httpPort = port;
        return this;
    }

    /**
     * Use this to specify which network interface to bind to.
     *
     * @param host The host to bind to, for example <code>"127.0.0.1"</code> to restrict connections from localhost
     *             only, or <code>"0.0.0.0"</code> to allow connections from the local network.
     * @return The current Mu Server Builder
     */
    public MuServerBuilder withInterface(String host) {
        this.host = host;
        return this;
    }

    /**
     * @param stopServerOnShutdown If true, then a shutdown hook which stops this server will be added to the JVM Runtime
     * @return The current Mu Server Builder
     */
    public MuServerBuilder addShutdownHook(boolean stopServerOnShutdown) {
        this.addShutdownHook = stopServerOnShutdown;
        return this;
    }

    /**
     * Enables gzip for certain resource types. The default is <code>true</code>. By default, the
     * gzippable resource types are taken from {@link ResourceType#getResourceTypes()} where
     * {@link ResourceType#gzip()} is <code>true</code>.
     *
     * @param enabled True to enable; false to disable
     * @return The current Mu Server builder
     * @see #withGzip(long, Set)
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
     *
     * @param minimumGzipSize The size in bytes before gzip is used. The default is 1400.
     * @param mimeTypesToGzip The mime-types that should be gzipped. In general, only text
     *                        files should be gzipped.
     * @return The current Mu Server Builder
     */
    public MuServerBuilder withGzip(long minimumGzipSize, Set<String> mimeTypesToGzip) {
        this.gzipEnabled = true;
        this.mimeTypesToGzip = mimeTypesToGzip;
        this.minimumGzipSize = minimumGzipSize;
        return this;
    }

    /**
     * Sets the HTTPS config. Defaults to {@link HttpsConfigBuilder#unsignedLocalhost()}}
     *
     * @param httpsConfig An HTTPS Config builder.
     * @return The current Mu Server Builder
     */
    public MuServerBuilder withHttpsConfig(HttpsConfigBuilder httpsConfig) {
        this.sslContextBuilder = httpsConfig;
        return this;
    }


    /**
     * Sets the HTTPS port to use. To set the SSL certificate config, see {@link #withHttpsConfig(HttpsConfigBuilder)}
     *
     * @param port A value of 0 will result in a random port being assigned; a value of -1 will
     *             disable HTTPS.
     * @return The current Mu Server builder
     */
    public MuServerBuilder withHttpsPort(int port) {
        this.httpsPort = port;
        return this;
    }

    /**
     * Sets the configuration for HTTP2
     *
     * @param http2Config A config
     * @return The current Mu Server builder
     * @see Http2ConfigBuilder
     */
    public MuServerBuilder withHttp2Config(Http2Config http2Config) {
        this.http2Config = http2Config;
        return this;
    }

    /**
     * Sets the configuration for HTTP2
     *
     * @param http2Config A config
     * @return The current Mu Server builder
     * @see Http2ConfigBuilder
     */
    public MuServerBuilder withHttp2Config(Http2ConfigBuilder http2Config) {
        return withHttp2Config(http2Config.build());
    }

    /**
     * Sets the thread executor service to run requests on. By default {@link Executors#newCachedThreadPool()}
     * is used.
     *
     * @param executor The executor service to use to handle requests
     * @return The current Mu Server builder
     */
    public MuServerBuilder withHandlerExecutor(ExecutorService executor) {
        this.executor = executor;
        return this;
    }

    /**
     * <p>The number of nio threads to handle requests.</p>
     * <p>Generally only a small number is required as NIO threads are only used for non-blocking
     * reads and writes of data. Request handlers are executed on a separate thread pool which
     * can be specified with {@link #withHandlerExecutor(ExecutorService)}.</p>
     * <p>Note that websocket callbacks are handled on these NIO threads.</p>
     *
     * @param nioThreads The nio threads. Default is 2 * processor's count but not more than 16
     * @return The current Mu Server builder
     */
    public MuServerBuilder withNioThreads(int nioThreads) {
        this.nioThreads = nioThreads;
        return this;
    }

    /**
     * <p>Specifies the maximum size in bytes of the HTTP request headers. Defaults to 8192.</p>
     * <p>If a request has headers exceeding this value, it will be rejected and a <code>431</code>
     * status code will be returned. Large values increase the risk of Denial-of-Service attacks
     * due to the extra memory allocated in each request.</p>
     * <p>It is recommended to not specify a value unless you are finding legitimate requests are
     * being rejected with <code>413</code> errors.</p>
     *
     * @param size The maximum size in bytes that can be used for headers.
     * @return The current Mu Server builder.
     */
    public MuServerBuilder withMaxHeadersSize(int size) {
        this.maxHeadersSize = size;
        return this;
    }

    /**
     * The maximum length that a URL can be. If it exceeds this value, a <code>414</code> error is
     * returned to the client. The default value is 8175.
     *
     * @param size The maximum number of characters allowed in URLs sent to this server.
     * @return The current Mu Server builder
     */
    public MuServerBuilder withMaxUrlSize(int size) {
        this.maxUrlSize = size;
        return this;
    }

    /**
     * The maximum allowed request body size. If exceeded, a 413 will be returned.
     *
     * @param maxSizeInBytes The maximum request body size allowed, in bytes. The default is 24MB.
     * @return The current Mu Server builder
     */
    public MuServerBuilder withMaxRequestSize(long maxSizeInBytes) {
        this.maxRequestSize = maxSizeInBytes;
        return this;
    }

    /**
     * Sets the idle timeout for connections. If no bytes are sent or received within this time then
     * the connection is closed.
     * <p>The default is 5 minutes.</p>
     *
     * @param duration The allowed timeout duration, or 0 to disable timeouts.
     * @param unit     The unit of the duration.
     * @return This builder
     * @see #withRequestTimeout(long, TimeUnit)
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
     * Sets the idle timeout for reading request bodies. If a slow client that is uploading a request body pauses
     * for this amount of time, the request will be closed (if the response has not started, the client will receive
     * a 408 error).
     * <p>The default is 2 minutes.</p>
     *
     * @param duration The allowed timeout duration, or 0 to disable timeouts.
     * @param unit     The unit of the duration.
     * @return This builder
     * @see #withIdleTimeout(long, TimeUnit)
     */
    public MuServerBuilder withRequestTimeout(long duration, TimeUnit unit) {
        if (duration < 0) {
            throw new IllegalArgumentException("The duration must be 0 or greater");
        }
        Mutils.notNull("unit", unit);
        this.requestReadTimeoutMillis = unit.toMillis(duration);
        return this;
    }


    /**
     * <p>Adds a request handler.</p>
     * <p>Note that handlers are executed in the order added to the builder, but all async
     * handlers are executed before synchronous handlers.</p>
     *
     * @param handler A handler builder. The <code>build()</code> method will be called on this
     *                to create the handler. If null, then no handler is added.
     * @return The current Mu Server Handler.
     * @see #addHandler(Method, String, RouteHandler)
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
     *
     * @param handler The handler to add. If null, then no handler is added.
     * @return The current Mu Server Handler.
     * @see #addHandler(Method, String, RouteHandler)
     */
    public MuServerBuilder addHandler(MuHandler handler) {
        if (handler != null) {
            handlers.add(handler);
        }
        return this;
    }

    /**
     * Registers a new handler that will only be called if it matches the given route info
     *
     * @param method      The method to match, or <code>null</code> to accept any method.
     * @param uriTemplate A URL template. Supports plain URLs like <code>/abc</code> or paths
     *                    with named parameters such as <code>/abc/{id}</code> or named parameters
     *                    with regexes such as <code>/abc/{id : [0-9]+}</code> where the named
     *                    parameter values can be accessed with the <code>pathParams</code>
     *                    parameter in the route handler.
     * @param handler     The handler to invoke if the method and URI matches. If null, then no handler is added.
     * @return Returns the server builder
     */
    public MuServerBuilder addHandler(Method method, String uriTemplate, RouteHandler handler) {
        if (handler == null) {
            return this;
        }
        return addHandler(Routes.route(method, uriTemplate, handler));
    }

    /**
     * Adds a listener that is notified when each response completes
     *
     * @param listener A listener. If null, then nothing is added.
     * @return Returns the server builder
     */
    public MuServerBuilder addResponseCompleteListener(ResponseCompleteListener listener) {
        if (listener != null) {
            if (this.responseCompleteListeners == null) {
                this.responseCompleteListeners = new ArrayList<>();
            }
            this.responseCompleteListeners.add(listener);
        }
        return this;
    }

    MuServerBuilder withWriteBufferWaterMark(int low, int high) {
        this.writeBufferWaterMark = new WriteBufferWaterMark(low, high);
        return this;
    }

    /**
     * <p>Adds a rate limiter to incoming requests.</p>
     * <p>The selector specified in this method allows you to control the limit buckets that are used. For
     * example, to set a limit on client IP addresses the selector would return {@link MuRequest#remoteAddress()}.</p>
     * <p>The selector also specifies the number of requests allowed for the bucket per time period, such that
     * different buckets can have different limits.</p>
     * <p>The following example shows how to allow 100 requests per second per IP address:</p>
     * <pre>
     *     {@code
     *     MuServerBuilder.httpsServer()
     *        .withRateLimiter(request -> RateLimit.builder()
     *                 .withBucket(request.remoteAddress())
     *                 .withRate(100)
     *                 .withWindow(1, TimeUnit.SECONDS)
     *                 .build())
     *     }
     * </pre>
     * <p>Note that multiple limiters can be added which allows different limits across different dimensions.
     * For example, you may allow 100 requests per second based on IP address and
     * also a limit based on a cookie, request path, or other value.</p>
     *
     * @param selector A function that returns a string based on the request, or null to not have a limit applied
     * @return This builder
     */
    public MuServerBuilder withRateLimiter(RateLimitSelector selector) {
        if (wheelTimer == null) {
            wheelTimer = new HashedWheelTimer(new DefaultThreadFactory("mu-limit-timer"));
            wheelTimer.start();
            rateLimiters = new ArrayList<>();
        }
        RateLimiterImpl rateLimiter = new RateLimiterImpl(selector, wheelTimer);
        this.rateLimiters.add(rateLimiter);
        return this;
    }


    /**
     * Sets the handler to use for exceptions thrown by other handlers, allowing for things such as custom error pages.
     * <p>Note that if the response has already started sending data, you will not be able to add a custom error
     * message. In this case, you may want to allow for the default error handling by returning <code>false</code>.</p>
     * <p>The following shows a pattern to filter out certain errors:</p>
     * <pre><code>
     * muServerBuilder.withExceptionHandler((request, response, exception) -&gt; {
     *     if (response.hasStartedSendingData()) return false; // cannot customise the response
     *     if (exception instanceof NotAuthorizedException) return false;
     *     response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
     *     response.write("Oh I'm worry, there was a problem");
     *     return true;
     * })
     * </code></pre>
     * @param exceptionHandler The handler to be called when an unhandled exception is encountered
     * @return This builder
     */
    public MuServerBuilder withExceptionHandler(UnhandledExceptionHandler exceptionHandler) {
        this.unhandledExceptionHandler = exceptionHandler;
        return this;
    }

    /**
     * @return The current value of this property
     */
    public long minimumGzipSize() {
        return minimumGzipSize;
    }

    /**
     * @return The current value of this property
     */
    public int httpPort() {
        return httpPort;
    }

    /**
     * @return The current value of this property
     */
    public int httpsPort() {
        return httpsPort;
    }

    /**
     * @return The current value of this property
     */
    public int maxHeadersSize() {
        return maxHeadersSize;
    }

    /**
     * @return The current value of this property
     */
    public int maxUrlSize() {
        return maxUrlSize;
    }

    /**
     * @return The current value of this property
     */
    public int nioThreads() {
        return nioThreads;
    }

    /**
     * @return The current value of this property
     */
    public List<MuHandler> handlers() {
        return Collections.unmodifiableList(handlers);
    }

    /**
     * @return The current value of this property
     */
    public boolean gzipEnabled() {
        return gzipEnabled;
    }

    /**
     * @return The current value of this property
     */
    public Set<String> mimeTypesToGzip() {
        return Collections.unmodifiableSet(mimeTypesToGzip);
    }

    /**
     * @return The current value of this property
     */
    public boolean addShutdownHook() {
        return addShutdownHook;
    }

    /**
     * @return The current value of this property
     */
    public String interfaceHost() {
        return host;
    }

    /**
     * @return The current value of this property
     */
    public HttpsConfigBuilder httpsConfigBuilder() {
        if (sslContextBuilder != null && !(sslContextBuilder instanceof HttpsConfigBuilder)) {
            throw new IllegalStateException("Please switch to using HttpsConfigBuilder to set HTTPS config");
        }
        return (HttpsConfigBuilder) sslContextBuilder;
    }

    /**
     * @return The current value of this property
     */
    public Http2Config http2Config() {
        return http2Config;
    }

    /**
     * @return The current value of this property
     */
    public long requestReadTimeoutMillis() {
        return requestReadTimeoutMillis;
    }

    /**
     * @return The current value of this property
     */
    public long idleTimeoutMills() {
        return idleTimeoutMills;
    }

    /**
     * @return The current value of this property
     */
    public ExecutorService executor() {
        return executor;
    }

    /**
     * @return The current value of this property
     */
    public long maxRequestSize() {
        return maxRequestSize;
    }

    /**
     * @return The current value of this property
     */
    public List<ResponseCompleteListener> responseCompleteListeners() {
        return Collections.unmodifiableList(responseCompleteListeners);
    }

    /**
     * @return The current value of this property
     */
    public List<RateLimiter> rateLimiters() {
        return rateLimiters.stream().map(RateLimiter.class::cast).collect(Collectors.toList());
    }

    /**
     * @return The current value of this property
     */
    public UnhandledExceptionHandler unhandledExceptionHandler() {
        return unhandledExceptionHandler;
    }

    /**
     * Creates a new server builder. Call {@link #withHttpsPort(int)} or {@link #withHttpPort(int)} to specify
     * the port to use, and call {@link #start()} to start the server.
     *
     * @return A new Mu Server builder
     */
    public static MuServerBuilder muServer() {
        return new MuServerBuilder();
    }

    /**
     * Creates a new server builder which will run as HTTP on a random port.
     *
     * @return A new Mu Server builder with the HTTP port set to 0
     */
    public static MuServerBuilder httpServer() {
        return muServer().withHttpPort(0);
    }

    /**
     * Creates a new server builder which will run as HTTPS on a random port.
     *
     * @return A new Mu Server builder with the HTTPS port set to 0
     */
    public static MuServerBuilder httpsServer() {
        return muServer().withHttpsPort(0);
    }


    public MuServer start3() {
        return Mu3ServerImpl.start(this);
    }

    public boolean useMu3 = true;

    /**
     * Creates and starts this server. An exception is thrown if it fails to start.
     *
     * @return The running server.
     */
    public MuServer start() {
        if (useMu3) {
            return start3();
        }
        if (httpPort < 0 && httpsPort < 0) {
            throw new IllegalArgumentException("No ports were configured. Please call MuServerBuilder.withHttpPort(int) or MuServerBuilder.withHttpsPort(int)");
        }

        ServerSettings settings = new ServerSettings(minimumGzipSize, maxHeadersSize, requestReadTimeoutMillis, maxRequestSize, maxUrlSize, gzipEnabled, mimeTypesToGzip, rateLimiters);

        ExecutorService handlerExecutor = this.executor;
        if (handlerExecutor == null) {
            DefaultThreadFactory threadFactory = new DefaultThreadFactory("muhandler");
            handlerExecutor = new ThreadPoolExecutor(8, 400, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), threadFactory);
        }
        NettyHandlerAdapter nettyHandlerAdapter = new NettyHandlerAdapter(handlerExecutor, handlers, responseCompleteListeners);

        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup workerGroup = new NioEventLoopGroup(this.nioThreads);
        List<Channel> channels = new ArrayList<>();

        ExecutorService finalHandlerExecutor = handlerExecutor;
        Runnable shutdown = () -> {
            try {
                if (wheelTimer != null) {
                    wheelTimer.stop();
                }
                for (Channel channel : channels) {
                    channel.close().sync();
                }
                finalHandlerExecutor.shutdown();
                bossGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
                workerGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();

            } catch (Exception e) {
                log.info("Error while shutting down. Will ignore. Error was: " + e.getMessage());
            }
        };

        try {
            GlobalTrafficShapingHandler trafficShapingHandler = new GlobalTrafficShapingHandler(workerGroup, 0, 0, 1000);
            MuStatsImpl stats = new MuStatsImpl(trafficShapingHandler.trafficCounter());
            SslContextProvider sslContextProvider = null;

            boolean http2Enabled = http2Config != null && http2Config.enabled;
            MuServerImpl server = new MuServerImpl(stats, http2Enabled, settings, unhandledExceptionHandler);

            Channel httpChannel = httpPort < 0 ? null : createChannel(bossGroup, workerGroup, nettyHandlerAdapter, host, httpPort, null, trafficShapingHandler, server, false, idleTimeoutMills, writeBufferWaterMark);
            Channel httpsChannel;
            if (httpsPort < 0) {
                httpsChannel = null;
            } else {
                HttpsConfigBuilder toUse = this.sslContextBuilder != null ? this.sslContextBuilder : HttpsConfigBuilder.unsignedLocalhost();
                SslContext nettySslContext = toUse.toNettySslContext(http2Enabled);
                log.debug("SSL Context is " + nettySslContext);
                sslContextProvider = new SslContextProvider(nettySslContext);
                httpsChannel = createChannel(bossGroup, workerGroup, nettyHandlerAdapter, host, httpsPort, sslContextProvider, trafficShapingHandler, server, http2Enabled, idleTimeoutMills, writeBufferWaterMark);
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
                ((SSLInfoImpl) sslContextProvider.sslInfo()).setHttpsUri(httpsUri);
            }

            InetSocketAddress serverAddress = (InetSocketAddress) channels.get(0).localAddress();
            server.onStarted(uri, httpsUri, shutdown, serverAddress, sslContextProvider);
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

    private static Channel createChannel(NioEventLoopGroup bossGroup, NioEventLoopGroup workerGroup, NettyHandlerAdapter nettyHandlerAdapter, String host, int port, SslContextProvider sslContextProvider, GlobalTrafficShapingHandler trafficShapingHandler, MuServerImpl server, final boolean http2, long idleTimeoutMills, WriteBufferWaterMark writeBufferWaterMark) throws InterruptedException {
        boolean usesSsl = sslContextProvider != null;
        String proto = usesSsl ? "https" : "http";
        ServerBootstrap b = new ServerBootstrap();
        b.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark);
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
                    boolean addAlpn = http2 && usesSsl;
                    if (addAlpn) {
                        p.addLast(BackPressureHandler.NAME, new BackPressureHandler());
                        p.addLast("alpn", new AlpnHandler(nettyHandlerAdapter, server, proto));
                    }
                    p.addLast("conerror", new ChannelInboundHandlerAdapter() {
                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            server.stats.onFailedToConnect();
                        }
                    });
                    if (!addAlpn) {
                        setupHttp1Pipeline(p, nettyHandlerAdapter, server, proto);
                    }
                }


            });
        ChannelFuture bound = host == null ? b.bind(port) : b.bind(host, port);
        return bound.sync().channel();
    }

    static void setupHttp1Pipeline(ChannelPipeline p, NettyHandlerAdapter nettyHandlerAdapter, MuServerImpl server, String proto) {
        p.addLast("decoder", new HttpRequestDecoder(server.settings().maxUrlSize + LENGTH_OF_METHOD_AND_PROTOCOL, server.settings().maxHeadersSize, 8192));
        p.addLast("encoder", new HttpResponseEncoder() {
            @Override
            protected boolean isContentAlwaysEmpty(HttpResponse msg) {
                return super.isContentAlwaysEmpty(msg) || msg instanceof NettyResponseAdaptor.EmptyHttpResponse;
            }
        });
        if (server.settings().gzipEnabled) {
            p.addLast("compressor", new SelectiveHttpContentCompressor(server.settings()));
        }
        p.addLast("keepalive", new HttpServerKeepAliveHandler());
        p.addLast("flowControl", new FlowControlHandler());
        p.addLast(BackPressureHandler.NAME, new BackPressureHandler());
        p.addLast("preread", new PreReader());
        p.addLast("muhandler", new Http1Connection(nettyHandlerAdapter, server, proto));
    }


    @Override
    public String toString() {
        return "MuServerBuilder{" +
            "minimumGzipSize=" + minimumGzipSize +
            ", httpPort=" + httpPort +
            ", httpsPort=" + httpsPort +
            ", maxHeadersSize=" + maxHeadersSize +
            ", maxUrlSize=" + maxUrlSize +
            ", nioThreads=" + nioThreads +
            ", handlers=" + handlers +
            ", gzipEnabled=" + gzipEnabled +
            ", mimeTypesToGzip=" + mimeTypesToGzip +
            ", addShutdownHook=" + addShutdownHook +
            ", host='" + host + '\'' +
            ", sslContextBuilder=" + sslContextBuilder +
            ", http2Config=" + http2Config +
            ", requestReadTimeoutMillis=" + requestReadTimeoutMillis +
            ", idleTimeoutMills=" + idleTimeoutMills +
            ", executor=" + executor +
            ", maxRequestSize=" + maxRequestSize +
            ", responseCompleteListeners=" + responseCompleteListeners +
            ", rateLimiters=" + rateLimiters +
            '}';
    }
}
