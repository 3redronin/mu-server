package io.muserver;

import io.muserver.handlers.ResourceType;
import io.muserver.rest.MuRuntimeDelegate;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * <p>A builder for creating a web server.</p>
 * <p>Use the <code>withXXX()</code> methods to set the ports, config, and request handlers needed.</p>
 */
public class MuServerBuilder {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    private int httpPort = -1;
    private int httpsPort = -1;
    private int maxHeadersSize = 8192;
    private int maxUrlSize = 8192;
    private final List<MuHandler> handlers = new ArrayList<>();
    private boolean addShutdownHook = false;
    private @Nullable String host;
    private @Nullable HttpsConfigBuilder sslContextBuilder;
    private @Nullable Http2Config http2Config;
    private long requestReadTimeoutMillis = TimeUnit.MINUTES.toMillis(2);
    private long idleTimeoutMills = TimeUnit.MINUTES.toMillis(20);
    private @Nullable ExecutorService executor;
    private @Nullable ExecutorService connectionExecutor;
    private @Nullable ScheduledExecutorService timerExecutor;
    private long maxRequestSize = 24 * 1024 * 1024;
    private @Nullable List<ResponseCompleteListener> responseCompleteListeners;
    private @Nullable List<RequestRejectListener> requestRejectListeners;
    @Nullable List<RateLimiterImpl> rateLimiters;
    private @Nullable UnhandledExceptionHandler unhandledExceptionHandler;
    private boolean autoHandleExpectContinue = true;
    private @Nullable List<ContentEncoder> contentEncoders = null;
    private @Nullable Path tempDirectory;

    /**
     * Sets the HTTP port to listen on.
     *
     * @param port The HTTP port to use. A value of <code>0</code> asks the operating system to assign an
     *             available port when the server starts; a value of <code>-1</code> disables the HTTP connector.
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
    public MuServerBuilder withInterface(@Nullable String host) {
        this.host = host;
        return this;
    }

    /**
     * Sets whether a JVM shutdown hook should stop the server automatically.
     *
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
     * @deprecated To disable content encoding, pass an empty list to {@link #withContentEncoders(List)}
     */
    @Deprecated
    public MuServerBuilder withGzipEnabled(boolean enabled) {
        if (enabled) {
            throw new UnsupportedOperationException("Use withContentEncoders to configure gzip");
        }
        return withContentEncoders(Collections.emptyList());
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
     * @deprecated GZIP is enabled by default. It can be replaced or customized with {@link #withContentEncoders(List)}
     */
    @Deprecated
    public MuServerBuilder withGzip(long minimumGzipSize, Set<String> mimeTypesToGzip) {
        return withContentEncoders(List.of(GZIPEncoderBuilder.gzipEncoder().withMimeTypesToGzip(mimeTypesToGzip).withMinGzipSize(minimumGzipSize).build()));
    }

    /**
     * Sets the HTTPS configuration to use when the HTTPS connector is enabled.
     *
     * @param httpsConfig An HTTPS config builder, or <code>null</code> to use the default HTTPS configuration
     *                    from {@link HttpsConfigBuilder#unsignedLocalhost()}, which creates a self-signed
     *                    certificate for localhost development.
     * @return The current Mu Server Builder
     */
    public MuServerBuilder withHttpsConfig(@Nullable HttpsConfigBuilder httpsConfig) {
        this.sslContextBuilder = httpsConfig;
        return this;
    }
    /**
     * Sets the directory that files can be temporarily written to, for example during file upload handling.
     *
     * @param tempDirectory The directory for temporary storage of data, for example uploaded files that exist on disk for the lifetime
     * of a request, or <code>null</code> to use the default java temporary location
     * @return This builder
     */
    public MuServerBuilder withTempDirectory(@Nullable Path tempDirectory) {
        this.tempDirectory = tempDirectory;
        return this;
    }
    /**
     * @return The directory for temporary storage of data, for example uploaded files that exist on disk for the lifetime
     * of a request, or <code>null</code> to use the default java temporary location
     */
    public @Nullable Path tempDirectory() {
        return tempDirectory;
    }


    /**
     * Sets the HTTPS port to listen on.
     * To set the TLS certificate configuration, see {@link #withHttpsConfig(HttpsConfigBuilder)}.
     *
     * @param port A value of <code>0</code> asks the operating system to assign an available port when the server
     *             starts; a value of <code>-1</code> disables the HTTPS connector.
     * @return The current Mu Server builder
     */
    public MuServerBuilder withHttpsPort(int port) {
        this.httpsPort = port;
        return this;
    }

    /**
     * Sets the HTTP/2 configuration for this server.
     *
     * @param http2Config The HTTP/2 configuration to use.
     * @return The current Mu Server builder
     * @see Http2ConfigBuilder
     */
    public MuServerBuilder withHttp2Config(@Nullable Http2Config http2Config) {
        this.http2Config = http2Config;
        return this;
    }

    /**
     * Sets the HTTP/2 configuration for this server from a builder.
     *
     * @param http2Config A builder for the HTTP/2 configuration, or <code>null</code> to leave the current setting unchanged.
     * @return The current Mu Server builder
     * @see Http2ConfigBuilder
     */
    public MuServerBuilder withHttp2Config(@Nullable Http2ConfigBuilder http2Config) {
        if (http2Config == null) {
            return this;
        }
        return withHttp2Config(http2Config.build());
    }

    /**
     * Sets the thread executor service to run requests on. By default, a
     * virtual-thread-per-task executor is used when the runtime supports virtual
     * threads, otherwise {@link Executors#newCachedThreadPool()} is used.
     *
     * @param executor The executor service to use to handle requests
     * @return The current Mu Server builder
     */
    public MuServerBuilder withHandlerExecutor(@Nullable ExecutorService executor) {
        this.executor = executor;
        return this;
    }

    /**
     * Sets the executor used for connection setup and HTTP/2 connection I/O.
     *
     * <p>HTTP/2 uses two long-lived tasks per connection: a socket reader and a write
     * coordinator. The executor must allow both tasks to make progress independently for
     * every active HTTP/2 connection, with additional capacity for short connection setup
     * and timed maintenance tasks. Request handlers do not run on this executor; they use
     * the executor configured by {@link #withHandlerExecutor(ExecutorService)}. Supplying
     * the same executor to both methods disables that isolation.</p>
     *
     * <p>By default, a server-owned virtual-thread-per-task executor is used when the
     * runtime supports virtual threads, otherwise a cached thread pool is used. A
     * caller-supplied executor remains owned by the caller and is not shut down when
     * the server stops.</p>
     *
     * @param connectionExecutor The executor for connection setup and HTTP/2 I/O, or
     *                           <code>null</code> to use the default
     * @return The current Mu Server builder
     */
    public MuServerBuilder withConnectionExecutor(@Nullable ExecutorService connectionExecutor) {
        this.connectionExecutor = connectionExecutor;
        return this;
    }

    /**
     * Sets the executor used to schedule server connection timers.
     *
     * <p>Timer threads only determine when work is due. Connection work is dispatched
     * to the executor configured by {@link #withConnectionExecutor(ExecutorService)}
     * so that timer threads do not perform socket I/O or invoke application handlers.</p>
     *
     * <p>The supplied scheduled executor must remain able to execute brief scheduling
     * callbacks promptly. It should not be shared with an executor that can be occupied
     * by long-running tasks.</p>
     *
     * <p>By default, a server-owned single-thread scheduled executor is used. A
     * caller-supplied executor remains owned by the caller and is not shut down when
     * the server stops.</p>
     *
     * @param timerExecutor The executor used to schedule connection timers, or
     *                      <code>null</code> to use the default
     * @return The current Mu Server builder
     */
    public MuServerBuilder withTimerExecutor(@Nullable ScheduledExecutorService timerExecutor) {
        this.timerExecutor = timerExecutor;
        return this;
    }

    /**
     * Obsolete. Throws {@link UnsupportedOperationException}
     * @param nioThreads ignored
     * @return Does not return
     * @deprecated NIO threads are no longer used
     */
    @Deprecated
    public MuServerBuilder withNioThreads(int nioThreads) {
        throw new UnsupportedOperationException("This is obsolete");
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
     * @throws IllegalArgumentException if the size is less than 3
     */
    public MuServerBuilder withMaxHeadersSize(int size) {
        if (size < 3) throw new IllegalArgumentException("The max headers size is too small");
        this.maxHeadersSize = size;
        return this;
    }

    /**
     * The maximum length that a URL can be. If it exceeds this value, a <code>414</code> error is
     * returned to the client. The default value is 8175.
     *
     * @param size The maximum number of characters allowed in URLs sent to this server.
     * @return The current Mu Server builder
     * @throws IllegalArgumentException if the size is less than 10
     */
    public MuServerBuilder withMaxUrlSize(int size) {
        if (size < 10) throw new IllegalArgumentException("max URL length is too small");
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
     * <p>The default is 20 minutes.</p>
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
     *                to create the handler. If <code>null</code>, then no handler is added.
     * @return The current Mu Server Handler.
     * @see #addHandler(Method, String, RouteHandler)
     */
    public MuServerBuilder addHandler(@Nullable MuHandlerBuilder<?> handler) {
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
     * @param handler The handler to add. If <code>null</code>, then no handler is added.
     * @return The current Mu Server Handler.
     * @see #addHandler(Method, String, RouteHandler)
     */
    public MuServerBuilder addHandler(@Nullable MuHandler handler) {
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
     * @param handler     The handler to invoke if the method and URI matches. If <code>null</code>,
     *                    then no handler is added.
     * @return Returns the server builder
     */
    public MuServerBuilder addHandler(Method method, String uriTemplate, @Nullable RouteHandler handler) {
        if (handler == null) {
            return this;
        }
        return addHandler(Routes.route(method, uriTemplate, handler));
    }

    /**
     * Adds a listener that is notified when each response completes
     *
     * @param listener A listener. If <code>null</code>, then nothing is added.
     * @return Returns the server builder
     */
    public MuServerBuilder addResponseCompleteListener(@Nullable ResponseCompleteListener listener) {
        if (listener != null) {
            if (this.responseCompleteListeners == null) {
                this.responseCompleteListeners = new ArrayList<>();
            }
            this.responseCompleteListeners.add(listener);
        }
        return this;
    }

    /**
     * Adds a listener that is notified when a request is rejected at the protocol level before it
     * becomes a normal request/response exchange (for example a <code>431</code> when the request
     * headers are too large). Such rejections are never reported to
     * {@link #addResponseCompleteListener(ResponseCompleteListener)}.
     *
     * @param listener A listener. If <code>null</code>, then nothing is added.
     * @return Returns the server builder
     */
    public MuServerBuilder addRequestRejectListener(@Nullable RequestRejectListener listener) {
        if (listener != null) {
            if (this.requestRejectListeners == null) {
                this.requestRejectListeners = new ArrayList<>();
            }
            this.requestRejectListeners.add(listener);
        }
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
        if (rateLimiters == null) {
            rateLimiters = new ArrayList<>();
        }
        this.rateLimiters.add(new RateLimiterImpl(selector));
        return this;
    }


    /**
     * Sets the handler to use for exceptions thrown by other handlers, allowing for things such as custom error pages.
     * <p>Note that if the response headers have already been written to the client, this will not be called as by then
     * it is too late to customize the response.</p>
     * <p>The following shows a pattern to filter out certain errors:</p>
     * <pre><code>
     * muServerBuilder.withExceptionHandler((request, response, exception) -&gt; {
     *     if (exception instanceof NotAuthorizedException) return false;
     *     response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
     *     response.write("Oh I'm worry, there was a problem");
     *     return true;
     * })
     * </code></pre>
     * @param exceptionHandler The handler to be called when an unhandled exception is encountered
     * @return This builder
     */
    public MuServerBuilder withExceptionHandler(@Nullable UnhandledExceptionHandler exceptionHandler) {
        this.unhandledExceptionHandler = exceptionHandler;
        return this;
    }

    /**
     * Gets the configured minimum size for gzip compression.
     *
     * @return This method always throws because gzip settings are now exposed via {@link #contentEncoders()}.
     * @deprecated use {@link #contentEncoders()} to find current settings
     */
    @Deprecated
    public long minimumGzipSize() {
        throw new UnsupportedOperationException("Use contentEncoders() for gzip configuration");
    }

    /**
     * Gets the configured HTTP port.
     *
     * @return The configured HTTP port, where <code>-1</code> means HTTP is disabled and <code>0</code> means an
     * ephemeral port will be chosen when the server starts.
     */
    public int httpPort() {
        return httpPort;
    }

    /**
     * Gets the configured HTTPS port.
     *
     * @return The configured HTTPS port, where <code>-1</code> means HTTPS is disabled and <code>0</code> means an
     * ephemeral port will be chosen when the server starts.
     */
    public int httpsPort() {
        return httpsPort;
    }

    /**
     * Gets the maximum allowed size of the request headers.
     *
     * @return The header size limit in bytes.
     */
    public int maxHeadersSize() {
        return maxHeadersSize;
    }

    /**
     * Gets the maximum allowed URL length.
     *
     * @return The URL length limit in characters.
     */
    public int maxUrlSize() {
        return maxUrlSize;
    }

    /**
     * Gets the configured number of NIO threads.
     *
     * @return Always returns <code>0</code> because NIO thread configuration is obsolete.
     * @deprecated There are no long nio threads in use
     */
    @Deprecated
    public int nioThreads() {
        return 0;
    }

    /**
     * Gets the request handlers registered with this builder.
     *
     * @return An unmodifiable view of the configured handlers in registration order.
     */
    public List<MuHandler> handlers() {
        return Collections.unmodifiableList(handlers);
    }

    /**
     * Indicates whether gzip encoding is currently enabled.
     *
     * @return True if the default encoders are in use or a gzip encoder is explicitly configured.
     * @deprecated check {@link #contentEncoders()} to see which encoders are configured
     */
    @Deprecated
    public boolean gzipEnabled() {
        return contentEncoders == null || contentEncoders.stream().anyMatch(e -> "gzip".equals(e.contentCoding()));
    }

    /**
     * Gets the MIME types configured for gzip compression.
     *
     * @return This method always throws because encoder configuration is now exposed via {@link #contentEncoders()}.
     * @deprecated encoders should be configured with {@link #withContentEncoders(List)}
     */
    @Deprecated
    public Set<String> mimeTypesToGzip() {
        throw new UnsupportedOperationException("Use contentEncoders() for gzip configuration");
    }

    /**
     * Indicates whether a JVM shutdown hook will be registered for the server.
     *
     * @return True if the server should be stopped automatically during JVM shutdown.
     */
    public boolean addShutdownHook() {
        return addShutdownHook;
    }

    /**
     * Gets the network interface host name or address to bind to.
     *
     * @return The configured host, or <code>null</code> to use the default bind address.
     */
    public @Nullable String interfaceHost() {
        return host;
    }

    /**
     * Gets the configured HTTPS builder.
     *
     * @return The HTTPS configuration builder, or <code>null</code> to use the default HTTPS configuration.
     */
    public @Nullable HttpsConfigBuilder httpsConfigBuilder() {
        return sslContextBuilder;
    }

    /**
     * @return The current HTTP2 configuration
     */
    public @Nullable Http2Config http2Config() {
        return http2Config;
    }

    /**
     * Gets the timeout used while reading request bodies.
     *
     * @return The request body read timeout in milliseconds, or <code>0</code> if disabled.
     */
    public long requestReadTimeoutMillis() {
        return requestReadTimeoutMillis;
    }

    /**
     * Gets the connection idle timeout.
     *
     * @return The idle timeout in milliseconds, or <code>0</code> if disabled.
     */
    public long idleTimeoutMills() {
        return idleTimeoutMills;
    }

    /**
     * Gets the executor used to run request handlers.
     *
     * @return The configured executor, or <code>null</code> if the default executor will be used.
     */
    public @Nullable ExecutorService executor() {
        return executor;
    }

    /**
     * Gets the executor used for connection setup and HTTP/2 connection I/O.
     *
     * @return The configured executor, or <code>null</code> if the default executor will be used.
     */
    public @Nullable ExecutorService connectionExecutor() {
        return connectionExecutor;
    }

    /**
     * Gets the executor used to schedule server connection timers.
     *
     * @return The configured executor, or <code>null</code> if the default executor will be used.
     */
    public @Nullable ScheduledExecutorService timerExecutor() {
        return timerExecutor;
    }

    /**
     * Gets the maximum allowed request body size.
     *
     * @return The request body size limit in bytes.
     */
    public long maxRequestSize() {
        return maxRequestSize;
    }

    /**
     * Gets the listeners notified after responses complete.
     *
     * @return An unmodifiable list of configured response-complete listeners, or an empty list if none were added.
     */
    public List<ResponseCompleteListener> responseCompleteListeners() {
        return responseCompleteListeners == null ? Collections.emptyList() : Collections.unmodifiableList(responseCompleteListeners);
    }

    /**
     * Gets the listeners notified when requests are rejected before normal request handling begins.
     *
     * @return An unmodifiable list of configured request reject listeners, or an empty list if none were added.
     */
    public List<RequestRejectListener> requestRejectListeners() {
        return requestRejectListeners == null ? Collections.emptyList() : Collections.unmodifiableList(requestRejectListeners);
    }

    /**
     * Gets the configured rate limiters.
     *
     * @return A list of configured rate limiters, or an empty list if none were added.
     */
    public List<RateLimiter> rateLimiters() {
        var rl = rateLimiters;
        if (rl == null) {
            return Collections.emptyList();
        }
        return rl.stream().map(RateLimiter.class::cast).collect(Collectors.toList());
    }

    /**
     * Gets the handler for uncaught exceptions from request handlers.
     *
     * @return The configured exception handler, or <code>null</code> if the default handling will be used.
     */
    public @Nullable UnhandledExceptionHandler unhandledExceptionHandler() {
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

    /**
     * Creates and starts this server. An exception is thrown if it fails to start.
     *
     * @return The running server.
     */
    public MuServer start() {
        if (httpPort < 0 && httpsPort < 0) {
            throw new IllegalArgumentException("No ports were configured. Please call MuServerBuilder.withHttpPort(int) or MuServerBuilder.withHttpsPort(int)");
        }
        try {
            return Mu3ServerImpl.start(this);
        } catch (IOException e) {
            throw new MuException("Error starting server", e);
        }
    }

    @Override
    public String toString() {
        return "MuServerBuilder{" +
            ", httpPort=" + httpPort +
            ", httpsPort=" + httpsPort +
            ", maxHeadersSize=" + maxHeadersSize +
            ", maxUrlSize=" + maxUrlSize +
            ", handlers=" + handlers +
            ", addShutdownHook=" + addShutdownHook +
            ", host='" + host + '\'' +
            ", sslContextBuilder=" + sslContextBuilder +
            ", http2Config=" + http2Config +
            ", requestReadTimeoutMillis=" + requestReadTimeoutMillis +
            ", idleTimeoutMills=" + idleTimeoutMills +
            ", executor=" + executor +
            ", connectionExecutor=" + connectionExecutor +
            ", timerExecutor=" + timerExecutor +
            ", maxRequestSize=" + maxRequestSize +
            ", responseCompleteListeners=" + responseCompleteListeners +
            ", requestRejectListeners=" + requestRejectListeners +
            ", rateLimiters=" + rateLimiters +
            '}';
    }

    /**
     * Gets the current expect-continue handling
     * @return <code>true</code> if mu-server will automatically handle `expect: 100-continue` headers; otherwise <code>false</code>
     */
    public boolean autoHandleExpectContinue() {
        return autoHandleExpectContinue;
    }

    /**
     * Specifies whether mu-server will handle `expect: 100-continue` request headers.
     *
     * <p>If <code>true</code> then mu-server will allow or reject requests based on the declared
     * content-length of the header, using the {@link #withMaxRequestSize(long)} value.</p>
     *
     * <p>If <code>false</code> then you will need to supply a handler that sends an informational
     * response on relevant requests using {@link MuResponse#sendInformationalResponse(HttpStatus, Headers)}.</p>
     *
     * <p><strong>Warning:</strong> if automatic handling is disabled and a <code>{@link HttpStatus#CONTINUE_100}</code>
     * is not sent then clients may hang waiting for a response.</p>
     *
     * <p>The default, which is highly recommended, is <code>true</code>.</p>
     * @param autoHandleExpectContinue <code>true</code> if mu-server will handle this situation; or <code>false</code>
     *                                 if you wish to handle these.
     * @return this builder
     */
    public MuServerBuilder withAutoHandleExpectContinue(boolean autoHandleExpectContinue) {
        this.autoHandleExpectContinue = autoHandleExpectContinue;
        return this;
    }

    /**
     * The response body content encoders in priority order, for example a GZIP compressor
     * @return the list of encoders to use, or null to use the default (GZIP only)
     */
    public @Nullable List<ContentEncoder> contentEncoders() {
        return contentEncoders;
    }

    /**
     * The response body content encoders in priority order, for example a GZIP compressor
     *
     * @param contentEncoders the list of encoders to use, or null to use the default (GZIP only)
     * @return this builder
     */
    public MuServerBuilder withContentEncoders(@Nullable List<ContentEncoder> contentEncoders) {
        this.contentEncoders = contentEncoders;
        return this;
    }

    /**
     * @return a virtual-thread-per-task executor if virtual threads are available; otherwise gets a cached thread pool.
     */
    static ExecutorService defaultExecutor() {
        // Executors.newVirtualThreadPerTaskExecutor()
        try {
            java.lang.reflect.Method newVirtualThreadPerTaskExecutor = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return (ExecutorService) newVirtualThreadPerTaskExecutor.invoke(null);
        } catch (Exception ignored) {
            // no worries; we'll use the default
        }
        return Executors.newCachedThreadPool();
    }

    private static final AtomicInteger TIMER_THREAD_IDS = new AtomicInteger();

    static ScheduledExecutorService defaultTimerExecutor() {
        var scheduler = new ScheduledThreadPoolExecutor(1, runnable ->
            new Thread(runnable, "mu-timer-" + TIMER_THREAD_IDS.incrementAndGet())
        );
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }
}
