package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.*;
import java.util.concurrent.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;

class NettyRequestAdapter implements MuRequest {
    private static final Logger log = LoggerFactory.getLogger(NettyRequestAdapter.class);
    private volatile RequestState state = RequestState.HEADERS_RECEIVED;
    final ChannelHandlerContext ctx;
    private final HttpRequest nettyRequest;
    private final URI serverUri;
    private final URI uri;
    private final Method method;
    private final Headers headers;
    private volatile RequestBodyReader requestBodyReader;
    private final RequestParameters query;

    private List<Cookie> cookies;
    private String contextPath = "";
    private String relativePath;
    private Map<String, Object> attributes;
    private volatile AsyncHandleImpl asyncHandle;
    private HttpExchange httpExchange;
    private final List<RequestStateChangeListener> listeners = new CopyOnWriteArrayList<>();

    NettyRequestAdapter(ChannelHandlerContext ctx, HttpRequest nettyRequest, Headers headers, Method method, String proto, String uri, String host) {
        this.ctx = ctx;
        this.nettyRequest = nettyRequest;
        this.serverUri = URI.create(proto + "://" + host + uri).normalize();
        this.headers = headers;
        this.uri = getUri(headers, proto, host, uri, serverUri);
        this.relativePath = this.uri.getRawPath();
        this.query = new NettyRequestParameters(new QueryStringDecoder(uri, true).parameters());
        this.method = method;
    }

    public boolean isAsync() {
        return asyncHandle != null;
    }

    @Override
    public String protocol() {
        return nettyRequest.protocolVersion().text();
    }

    @Override
    public HttpVersion httpVersion() {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public HttpConnection connection() {
        return this.httpExchange.connection();
    }

    @Override
    public Headers trailers() {
        return null;
    }

    private static URI getUri(Headers h, String scheme, String hostHeader, String requestUri, URI serverUri) {
        try {
            List<ForwardedHeader> forwarded = h.forwarded();
            if (forwarded.isEmpty()) {
                return serverUri;
            }
            ForwardedHeader f = forwarded.get(0);

            String originalScheme = Mutils.coalesce(f.proto(), scheme);
            String host = Mutils.coalesce(f.host(), hostHeader);
            return new URI(originalScheme + "://" + host + requestUri).normalize();
        } catch (Exception e) {
            log.warn("Could not create a URI object using header values " + h
                + " so using local server URI. URL generation (including in redirects) may be incorrect.");
            return serverUri;
        }
    }

    @Override
    public boolean hasBody() {
        return inputStream().isPresent();
    }

    @Override
    public String contentType() {
        String c = headers.get(HttpHeaderNames.CONTENT_TYPE);
        if (c == null) return null;
        if (c.contains(";")) {
            return c.split(";")[0];
        }
        return c;
    }

    @Override
    public long startTime() {
        return httpExchange.startTime();
    }

    public Method method() {
        return method;
    }


    public URI uri() {
        return uri;
    }


    public URI serverURI() {
        return serverUri;
    }


    public Headers headers() {
        return headers;
    }

    public long maxRequestBytes() {
        return server().maxRequestSize();
    }

    public Optional<InputStream> inputStream() {
        if (!headers().hasBody()) {
            return Optional.empty();
        }
        RequestBodyReader rbr = this.requestBodyReader;
        if (rbr == null) {
            RequestBodyReaderInputStreamAdapter inputStreamReader = new RequestBodyReaderInputStreamAdapter(maxRequestBytes());
            try {
                claimingBodyRead(inputStreamReader).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MuException("Interrupted while waiting to get request body input stream");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Error) throw (Error) cause;
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new MuException("Error while getting input stream", cause);
            }
            return Optional.of(inputStreamReader.inputStream());
        } else if (rbr instanceof RequestBodyReaderInputStreamAdapter) {
            return Optional.of(((RequestBodyReaderInputStreamAdapter) rbr).inputStream());
        } else {
            throw new IllegalStateException("Cannot read the body as an input stream when the body is already being read with a " + rbr.getClass());
        }
    }

    public String readBodyAsString() throws IOException {
        if (headers.hasBody()) {
            RequestBodyReader.StringRequestBodyReader reader = createStringRequestBodyReader(maxRequestBytes(), headers());
            claimingBodyRead(reader);
            reader.blockUntilFullyRead();
            return reader.body();
        } else {
            return "";
        }
    }

    static RequestBodyReader.StringRequestBodyReader createStringRequestBodyReader(long maxSize, Headers headers) {
        Charset bodyCharset = bodyCharset(headers, true);
        return new RequestBodyReader.StringRequestBodyReader(maxSize, bodyCharset);
    }

    static Charset bodyCharset(Headers headers, boolean isRequest) {
        MediaType mediaType = headers.contentType();
        Charset bodyCharset = UTF_8;
        if (mediaType != null) {
            String charset = mediaType.getParameters().get("charset");
            if (!Mutils.nullOrEmpty(charset)) {
                try {
                    bodyCharset = Charset.forName(charset);
                } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                    if (isRequest) {
                        throw new ClientErrorException("Invalid request body charset", 400);
                    } else {
                        log.error("Invalid response body charset: " + mediaType, e);
                        throw new ServerErrorException("Invalid response body charset", 500);
                    }
                }
            }
        }
        return bodyCharset;
    }

    private io.netty.util.concurrent.Future<?> claimingBodyRead(RequestBodyReader reader) {
        if (requestBodyReader != null) {
            throw new IllegalStateException("The body of the request message cannot be read twice. This can happen when calling any 2 of inputStream(), readBodyAsString(), or form() methods.");
        }
        if (!ctx.executor().inEventLoop()) {
            return ctx.executor().submit(() -> claimingBodyRead(reader));
        }
        if (!state.endState()) {
            requestBodyReader = reader;
            setState(RequestState.RECEIVING_BODY);
            httpExchange.scheduleReadTimeout();
            return ctx.newSucceededFuture();
        } else {
            log.warn("Request body reader set after state is " + state);
            return ctx.newFailedFuture(new IllegalStateException("Cannot claim body when state is " + state));
        }
    }

    void discardInputStreamIfNotConsumed() {
        if (requestBodyReader == null) {
            claimingBodyRead(new RequestBodyReader.DiscardingReader(maxRequestBytes()));
        }
    }

    @Override
    public List<UploadedFile> uploadedFiles(String name) throws IOException {
        ensureFormDataLoaded();
        return ((RequestBodyReader.MultipartFormReader) requestBodyReader).uploads(name);
    }

    @Override
    public UploadedFile uploadedFile(String name) throws IOException {
        List<UploadedFile> uploadedFiles = uploadedFiles(name);
        return uploadedFiles.isEmpty() ? null : uploadedFiles.get(0);
    }


    @Override
    public RequestParameters query() {
        return query;
    }

    @Override
    public MuForm form() throws IOException {
        ensureFormDataLoaded();
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Cookie> cookies() {
        if (this.cookies == null) {
            List<String> encoded = headers().getAll(HeaderNames.COOKIE);
            if (encoded.isEmpty()) {
                this.cookies = emptyList();
            } else {
                List<Cookie> theList = new ArrayList<>();
                for (String val : encoded) {
                }
                this.cookies = Collections.unmodifiableList(theList);
            }
        }
        return this.cookies;
    }

    @Override
    public Optional<String> cookie(String name) {
        List<Cookie> cookies = cookies();
        for (Cookie cookie : cookies) {
            if (cookie.name().equals(name)) {
                return Optional.of(cookie.value());
            }
        }
        return Optional.empty();
    }

    @Override
    public String contextPath() {
        return contextPath;
    }

    @Override
    public String relativePath() {
        return relativePath;
    }

    @Override
    public Object attribute(String key) {
        Mutils.notNull("key", key);
        if (attributes == null) {
            return null;
        }
        return attributes.get(key);
    }

    @Override
    public void attribute(String key, Object value) {
        Mutils.notNull("key", key);
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        attributes.put(key, value);
    }

    @Override
    public Map<String, Object> attributes() {
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        return attributes;
    }

    @Override
    public AsyncHandle handleAsync() {
        if (isAsync()) {
            return asyncHandle;
        }
        asyncHandle = new AsyncHandleImpl(this, httpExchange);
        return asyncHandle;
    }

    @Override
    public String remoteAddress() {
        return connection().remoteAddress().getHostString();
    }

    @Override
    public String clientIP() {
        List<ForwardedHeader> forwarded = headers.forwarded();
        for (ForwardedHeader forwardedHeader : forwarded) {
            if (forwardedHeader.forValue() != null) {
                return forwardedHeader.forValue();
            }
        }
        return this.connection().remoteAddress().getHostString();
    }

    @Override
    public MuServer server() {
        return connection().server();
    }

    private void ensureFormDataLoaded() throws IOException {
        if (requestBodyReader == null) {
            String ct = contentType();
            RequestBodyReader reader;
            if (ct.startsWith("multipart/")) {
                reader = new RequestBodyReader.MultipartFormReader(maxRequestBytes(), nettyRequest, bodyCharset(headers, true));
                claimingBodyRead(reader);
            } else if (ct.equals("application/x-www-form-urlencoded")) {
                reader = new RequestBodyReader.UrlEncodedBodyReader(createStringRequestBodyReader(maxRequestBytes(), headers()));
                claimingBodyRead(reader);
            } else {
                throw new ServerErrorException("", 500);
            }
            reader.blockUntilFullyRead();
        } else if (!(requestBodyReader instanceof FormRequestBodyReader)) {
            throw new IllegalStateException("Cannot load form data when the body is being read with a " + requestBodyReader);
        }
    }

    public String toString() {
        return method().name() + " " + uri();
    }

    void addContext(String contextToAdd) {
        contextToAdd = normaliseContext(contextToAdd);
        this.contextPath = this.contextPath + contextToAdd;
        this.relativePath = this.relativePath.substring(contextToAdd.length());
    }

    void setPaths(String contextPath, String relativePath) {
        this.contextPath = contextPath;
        this.relativePath = relativePath;
    }

    private static String normaliseContext(String contextToAdd) {
        if (contextToAdd.endsWith("/")) {
            contextToAdd = contextToAdd.substring(0, contextToAdd.length() - 1);
        }
        if (!contextToAdd.startsWith("/")) {
            contextToAdd = "/" + contextToAdd;
        }
        return contextToAdd;
    }

    void onCancelled(ResponseState reason, Throwable ex) {
        if (!state.endState()) {
            if (requestBodyReader != null && !requestBodyReader.completed()) {
                requestBodyReader.onCancelled(ex);
            }
            setState(RequestState.ERRORED);
        }
    }

    boolean websocketUpgrade(MuWebSocket muWebSocket, HttpHeaders responseHeaders, long idleReadTimeoutMills, long pingAfterWriteMillis, int maxFramePayloadLength) {
        String url = "ws" + uri().toString().substring(4);
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(url, null, false, maxFramePayloadLength);

        DefaultFullHttpRequest fullReq = new DefaultFullHttpRequest(nettyRequest.protocolVersion(), nettyRequest.method(), nettyRequest.uri(), Unpooled.EMPTY_BUFFER, nettyRequest.headers(), EmptyHttpHeaders.INSTANCE);
        WebSocketServerHandshaker handshaker = factory.newHandshaker(fullReq);
        if (handshaker == null) {
            throw new UnsupportedOperationException();
        }

        ctx.channel().pipeline().replace("idle", "idle",
            new IdleStateHandler(idleReadTimeoutMills, pingAfterWriteMillis, 0, TimeUnit.MILLISECONDS));
        MuWebSocketSessionImpl session = new MuWebSocketSessionImpl(ctx, muWebSocket, connection());
        handshaker.handshake(ctx.channel(), fullReq, responseHeaders, ctx.channel().newPromise())
            .addListener(future -> {
                if (future.isSuccess()) {
                    ctx.pipeline().fireUserEventTriggered(new ExchangeUpgradeEvent(session));
                } else {
                    ctx.pipeline().fireUserEventTriggered(new MuExceptionFiredEvent(httpExchange, 0, future.cause()));
                }
            });

        return true;
    }

    public void setExchange(HttpExchange httpExchange) {
        if (httpExchange == null) {
            throw new IllegalStateException("Exchange was already set");
        }
        this.httpExchange = httpExchange;
    }

    void addChangeListener(RequestStateChangeListener listener) {
        this.listeners.add(listener);
    }

    void setState(RequestState status) {
        assert httpExchange.inLoop() : "Not in event loop";
        RequestState oldState = this.state;
        if (oldState.endState()) {
            throw new IllegalStateException("Didn't expect to get a status update to " + status + " when the current status is " + oldState);
        }
        this.state = status;
        for (RequestStateChangeListener listener : listeners) {
            listener.onChange(httpExchange, status);
        }
    }

    void cleanup() {
        if (requestBodyReader != null) {
            requestBodyReader.cleanup();
            requestBodyReader = null;
        }
    }

    public RequestState requestState() {
        return state;
    }

    @Override
    public void abort() {

    }

    void onRequestBodyRead(ByteBuf content, boolean last, DoneCallback callback) {
        RequestBodyReader rbr = this.requestBodyReader;
        if (rbr != null) {
            rbr.onRequestBodyRead(content, last, callback);
        } else {
            throw new IllegalStateException("Got content before a request body reader was set");
        }
    }

    void onReadTimeout() {
        if (requestBodyReader != null && !state.endState()) {
            requestBodyReader.onCancelled(new TimeoutException());
        }
    }

    public HttpExchange exchange() {
        return httpExchange;
    }

    static class AsyncHandleImpl implements AsyncHandle {

        private final NettyRequestAdapter request;
        private final HttpExchange httpExchange;

        private AsyncHandleImpl(NettyRequestAdapter request, HttpExchange httpExchange) {
            this.request = request;
            this.httpExchange = httpExchange;
        }

        @Override
        public void setReadListener(RequestBodyListener readListener) {
            if (request.state.endState()) {
                readListener.onComplete();
            } else {
                request.claimingBodyRead(new RequestBodyReader.ListenerAdapter(this, request.maxRequestBytes(), readListener));
            }
        }

        @Override
        public void complete() {
            if (!httpExchange.state().endState()) {
                if (!httpExchange.inLoop()) {
                    httpExchange.ctx.executor().execute(this::complete);
                } else {
                    httpExchange.complete();
                }
            }
        }

        @Override
        public void sendInformationalResponse(HttpStatusCode status, DoneCallback callback) {

        }

        @Override
        public void complete(Throwable throwable) {
            if (throwable == null) {
                complete();
            } else {
                if (!httpExchange.state().endState()) {
                    NettyHandlerAdapter.useCustomExceptionHandlerOrFireIt(httpExchange, throwable);
                }
            }
        }

        @Override
        public void write(ByteBuffer data, DoneCallback callback) {
            ChannelFuture writeFuture = (ChannelFuture) write(data);
            writeFuture.addListener(future -> {
                try {
                    if (future.isSuccess()) {
                        callback.onComplete(null);
                    } else {
                        callback.onComplete(future.cause());
                    }
                } catch (Throwable e) {
                    log.warn("Unhandled exception from write callback", e);
                    callback.onComplete(e);
                }
            });
        }

        @Override
        public Future<Void> write(ByteBuffer data) {
            NettyResponseAdaptor response = request.httpExchange.response;
            try {
                return response.writeAndFlush(data);
            } catch (Throwable e) {
                return request.ctx.channel().newFailedFuture(e);
            }
        }

        @Override
        public void addResponseCompleteHandler(ResponseCompleteListener responseCompleteListener) {
            this.httpExchange.addChangeListener((exchange, newState) -> {
                if (newState.endState()) {
                    responseCompleteListener.onComplete(exchange);
                }
            });
        }

        @Override
        public void readForm(FormConsumer formConsumer) {

        }

    }

}

