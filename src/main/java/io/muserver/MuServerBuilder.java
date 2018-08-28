package io.muserver;

import io.muserver.handlers.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>A builder for creating a web server.</p>
 * <p>Use the <code>withXXX()</code> methods to set the ports, config, and request handlers needed.</p>
 */
public class MuServerBuilder {
    private static final Logger log = LoggerFactory.getLogger(MuServerBuilder.class);
    private long minimumGzipSize = 1400;
    private int httpPort = -1;
    private int httpsPort = -1;
    private int maxHeadersSize = RequestParser.Options.defaultOptions.maxHeaderSize;
    private int maxUrlSize = RequestParser.Options.defaultOptions.maxUrlLength;
    private List<MuHandler> handlers = new ArrayList<>();
    private SSLContext sslContext;
    private boolean gzipEnabled = true;
    private Set<String> mimeTypesToGzip = ResourceType.gzippableMimeTypes(ResourceType.getResourceTypes());
    private boolean addShutdownHook = false;
    private String host;
    private ExecutorService executorService;

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
     * Sets the executor that request handlers will run on. Defaults to <code>Executors.newCachedThreadPool()</code>
     * @param executor The executor to use
     * @return The current Mu-Server Builder
     */
    public MuServerBuilder withExecutor(ExecutorService executor) {
        this.executorService = executor;
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
     * <p>Adds a request handler.</p>
     * <p>Note that handlers are executed in the order added to the builder, but all async
     * handlers are executed before synchronous handlers.</p>
     * @see #addHandler(Method, String, RouteHandler)
     * @param handler A handler builder. The <code>build()</code> method will be called on this
     *                to create the handler.
     * @return The current Mu-Server Handler.
     */
    public MuServerBuilder addHandler(MuHandlerBuilder handler) {
        return addHandler(handler.build());
    }

    /**
     * <p>Adds a request handler.</p>
     * <p>Note that handlers are executed in the order added to the builder, but all async
     * handlers are executed before synchronous handlers.</p>
     * @see #addHandler(Method, String, RouteHandler)
     * @param handler The handler to add.
     * @return The current Mu-Server Handler.
     */
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


    /**
     * Creates and starts this server. An exception is thrown if it fails to start.
     * @return The running server.
     */
    public MuServer start() {
        if (httpPort < 0 && httpsPort < 0) {
            throw new IllegalArgumentException("No ports were configured. Please call MuServerBuilder.withHttpPort(int) or MuServerBuilder.withHttpsPort(int)");
        }

        ExecutorService executorService = this.executorService != null ? this.executorService : Executors.newCachedThreadPool();

        List<MuHandler> handlersCopy = Collections.unmodifiableList(new ArrayList<>(handlers));

        AtomicReference<MuServer> serverRef = new AtomicReference<>();

        List<ConnectionAcceptor> acceptors = new ArrayList<>();
        RequestParser.Options parserOptions = new RequestParser.Options(maxUrlSize, maxHeadersSize);
        URI httpUri = startAcceptorMaybe(executorService, handlersCopy, serverRef, acceptors, httpPort, host, null, parserOptions);
        URI httpsUri = startAcceptorMaybe(executorService, handlersCopy, serverRef, acceptors, httpsPort, host, sslContext == null ? SSLContextBuilder.unsignedLocalhostCert() : sslContext, parserOptions);
        Runnable shutdown = () -> {
            try {
                for (ConnectionAcceptor acceptor : acceptors) {
                    try {
                        acceptor.stop();
                    } catch (InterruptedException e) {
                        log.warn("Error while stopping " + acceptor, e);
                    }
                }
            } catch (Exception e) {
                log.info("Error while shutting down. Will ignore. Error was: " + e.getMessage());
            }
        };


        MuStats stats = new MuStatsImpl2();
        MuServer server = new MuServerImpl(httpUri, httpsUri, shutdown, stats, acceptors.get(0).address);
        serverRef.set(server);
        if (addShutdownHook) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        }
        return server;
    }

    private static URI startAcceptorMaybe(ExecutorService executorService, List<MuHandler> handlers, AtomicReference<MuServer> serverRef, List<ConnectionAcceptor> accepters, int httpPort, String host, SSLContext sslContext, RequestParser.Options parserOptions) {
        URI httpUri = null;
        if (httpPort >= 0) {
            ConnectionAcceptor httpAccepter = new ConnectionAcceptor(executorService, handlers, sslContext, serverRef, parserOptions);
            try {
                httpAccepter.start(host, httpPort);
                httpUri = httpAccepter.uri();
                accepters.add(httpAccepter);
            } catch (Exception e) {
                throw new RuntimeException("Error while starting HTTP service on port " + httpPort, e);
            }
        }
        return httpUri;
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
