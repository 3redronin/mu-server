package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;

import static io.muserver.Cookie.nettyToMu;
import static io.muserver.HttpExchange.dealWithUnhandledException;
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
    private RequestParameters form;
    private List<Cookie> cookies;
    private String contextPath = "";
    private String relativePath;
    private HttpPostMultipartRequestDecoder multipartRequestDecoder;
    private HashMap<String, List<UploadedFile>> uploads;
    private Map<String, Object> attributes;
    private volatile AsyncHandleImpl asyncHandle;
    private final boolean keepalive;
    private HttpExchange httpExchange;
    private final List<RequestStateChangeListener> listeners = new CopyOnWriteArrayList<>();

    NettyRequestAdapter(ChannelHandlerContext ctx, HttpRequest nettyRequest, Headers headers, Method method, String proto, String uri, boolean keepalive, String host) {
        this.ctx = ctx;
        this.nettyRequest = nettyRequest;
        this.keepalive = keepalive;
        this.serverUri = URI.create(proto + "://" + host + uri).normalize();
        this.headers = headers;
        this.uri = getUri(headers, proto, host, uri, serverUri);
        this.relativePath = this.uri.getRawPath();
        this.query = new NettyRequestParameters(new QueryStringDecoder(uri, true));
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
    public HttpConnection connection() {
        return this.httpExchange.connection();
    }

    boolean isKeepAliveRequested() {
        return keepalive;
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


    public Optional<InputStream> inputStream() {
        RequestBodyReader rbr = this.requestBodyReader;
        if (!headers().hasBody()) {
            return Optional.empty();
        }
        if (rbr == null) {
            RequestBodyReaderInputStreamAdapter inputStream = new RequestBodyReaderInputStreamAdapter();
            claimingBodyRead(inputStream);
            return Optional.of(inputStream);
        } else if (rbr instanceof RequestBodyReaderInputStreamAdapter) {
            return Optional.of((InputStream) rbr);
        } else {
            throw new IllegalStateException("Cannot read the body as an input stream when the body is already being read with a " + rbr.getClass());
        }
    }

    public String readBodyAsString() throws IOException {
        MediaType mediaType = headers().contentType();
        Charset bodyCharset = UTF_8;
        if (mediaType != null) {
            String charset = mediaType.getParameters().get("charset");
            if (!Mutils.nullOrEmpty(charset)) {
                bodyCharset = Charset.forName(charset);
            }
        }
        int len = headers().getInt("content-length", -1);
        if (len == 0) {
            claimingBodyRead(new RequestBodyReader.DiscardingReader());
            return "";
        }
        StringRequestBodyReader reader = new StringRequestBodyReader(bodyCharset, len);
        claimingBodyRead(reader);
        try {
            return reader.future().get();
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        } catch (ExecutionException e) {
            throw new IOException("Error reading request body", e.getCause() == null ? e : e.getCause());
        }
    }

    private void claimingBodyRead(RequestBodyReader reader) {
        if (requestBodyReader != null) {
            throw new IllegalStateException("The body of the request message cannot be read twice. This can happen when calling any 2 of inputStream(), readBodyAsString(), or form() methods.");
        }
        log.info("Setting request body reader " + reader);
        requestBodyReader = reader;
        ctx.channel().read();
    }

    void discardInputStreamIfNotConsumed() {
        if (requestBodyReader == null) {
            claimingBodyRead(new RequestBodyReader.DiscardingReader());
        }
    }

    @Override
    public List<UploadedFile> uploadedFiles(String name) throws IOException {
        ensureFormDataLoaded();
        List<UploadedFile> list = uploads.get(name);
        return list == null ? emptyList() : list;
    }

    @Override
    public UploadedFile uploadedFile(String name) throws IOException {
        List<UploadedFile> uploadedFiles = uploadedFiles(name);
        return uploadedFiles.isEmpty() ? null : uploadedFiles.get(0);
    }

    private void addFile(String name, UploadedFile file) {
        if (!uploads.containsKey(name)) {
            uploads.put(name, new ArrayList<>());
        }
        uploads.get(name).add(file);
    }

    @Deprecated
    public String parameter(String name) {
        return query().get(name);
    }

    @Override
    public RequestParameters query() {
        return query;
    }

    @Override
    public RequestParameters form() throws IOException {
        ensureFormDataLoaded();
        return form;
    }

    @Deprecated
    public List<String> parameters(String name) {
        return query.getAll(name);
    }

    @Deprecated
    public String formValue(String name) throws IOException {
        return form().get(name);
    }

    @Deprecated
    public List<String> formValues(String name) throws IOException {
        return form().getAll(name);
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
                    theList.addAll(nettyToMu(ServerCookieDecoder.STRICT.decode(val)));
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
    @Deprecated
    public Object state() {
        return attribute("_value_");
    }

    @Override
    @Deprecated
    public void state(Object value) {
        attribute("_value_", value);
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
    public MuServer server() {
        return connection().server();
    }

    private void ensureFormDataLoaded() throws IOException {
        if (form == null) {
            if (contentType().startsWith("multipart/")) {
                if (multipartRequestDecoder == null) throw new RuntimeException("TODO: switch to dedicated form reader");
                multipartRequestDecoder = new HttpPostMultipartRequestDecoder(nettyRequest);
                InputStream inputStream = inputStream().orElseThrow(() -> new ClientErrorException("A request with a content-type of " + contentType() + " was made with no request body", 400));
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = inputStream.read(buffer)) > -1) {
                    if (read > 0) {
                        ByteBuf content = Unpooled.copiedBuffer(buffer, 0, read);
                        multipartRequestDecoder.offer(new DefaultHttpContent(content));
                    }
                }
                multipartRequestDecoder.offer(new DefaultLastHttpContent());
                uploads = new HashMap<>();

                List<InterfaceHttpData> bodyHttpDatas = multipartRequestDecoder.getBodyHttpDatas();
                QueryStringEncoder qse = new QueryStringEncoder("/");

                for (InterfaceHttpData bodyHttpData : bodyHttpDatas) {
                    if (bodyHttpData instanceof FileUpload) {
                        FileUpload fileUpload = (FileUpload) bodyHttpData;
                        if (fileUpload.length() == 0 && Mutils.nullOrEmpty(fileUpload.getFilename())) {
                            // nothing uploaded
                        } else {
                            UploadedFile uploadedFile = new MuUploadedFile(fileUpload);
                            addFile(fileUpload.getName(), uploadedFile);
                        }
                    } else if (bodyHttpData instanceof Attribute) {
                        Attribute a = (Attribute) bodyHttpData;
                        qse.addParam(a.getName(), a.getValue());
                    } else {
                        log.warn("Unrecognised body part: " + bodyHttpData.getClass() + " from " + this + " - this may mean some of the request data is lost.");
                    }
                }
                form = new NettyRequestParameters(new QueryStringDecoder(qse.toString(), UTF_8, true, 1000000));
            } else {
                String body = readBodyAsString();
                form = new NettyRequestParameters(new QueryStringDecoder(body, UTF_8, false, 1000000));
            }
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

    void clean() {
        state(null);
        if (multipartRequestDecoder != null) {
            multipartRequestDecoder.destroy();
            multipartRequestDecoder = null;
        }
    }

    void onCancelled(ResponseState reason) {
        if (asyncHandle != null) {
            asyncHandle.onCancelled(reason);
        }
    }

    boolean websocketUpgrade(MuWebSocket muWebSocket, HttpHeaders responseHeaders, long idleReadTimeoutMills, long pingAfterWriteMillis, int maxFramePayloadLength) {
        String url = "ws" + uri().toString().substring(4);
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(url, null, false, maxFramePayloadLength);

        DefaultFullHttpRequest fullReq = new DefaultFullHttpRequest(nettyRequest.protocolVersion(), nettyRequest.method(), nettyRequest.uri(), new EmptyByteBuf(ByteBufAllocator.DEFAULT), nettyRequest.headers(), EmptyHttpHeaders.INSTANCE);
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
                    session.onConnect();
                    ctx.pipeline().fireUserEventTriggered(new ExchangeUpgradeEvent(session));
                } else {
                    ctx.pipeline().fireUserEventTriggered(new ExchangeUpgradeEvent(null));
                }
            });

        return true;
    }

    public void setExchange(HttpExchange httpExchange) {
        if (httpExchange == null) {
            throw new IllegalStateException("Exchange was already set");
        }
        log.info("Setting exchange for type " + httpExchange);
        this.httpExchange = httpExchange;
    }

    void addChangeListener(RequestStateChangeListener listener) {
        this.listeners.add(listener);
    }

    void setState(RequestState status) {
        RequestState oldState = this.state;
        if (oldState.endState()) {
            throw new IllegalStateException("Didn't expect to get a status update to " + status + " when the current status is " + oldState);
        }
        this.state = status;
        for (RequestStateChangeListener listener : listeners) {
            listener.onChange(httpExchange, status);
        }
    }

    public RequestState requestState() {
        return state;
    }

    void onRequestBodyRead(ByteBuf content, boolean last, DoneCallback callback) {
        RequestBodyReader rbr = this.requestBodyReader;
        if (rbr == null) {
            throw new IllegalStateException("Got content before a request body reader was set");
        } else {
            rbr.onRequestBodyRead(content, last, callback);
        }
    }

    static class AsyncHandleImpl implements AsyncHandle, ConnectionState.Listener {

        public final boolean isConnectionStateSupported;
        private final NettyRequestAdapter request;
        private final HttpExchange httpExchange;
        private LinkedList<DoneCallback> doneCallbackList;

        private AsyncHandleImpl(NettyRequestAdapter request, HttpExchange httpExchange) {
            this.request = request;
            this.httpExchange = httpExchange;
            this.isConnectionStateSupported = request.connection() instanceof ConnectionState;
            if (isConnectionStateSupported) {
                ((ConnectionState) request.connection()).registerConnectionStateListener(this);
            }
        }

        @Override
        public void setReadListener(RequestBodyListener readListener) {
            log.info("Setting read listener " + readListener);
            request.claimingBodyRead(new RequestBodyReader.ListenerAdapter(readListener));
        }

        private void clearDoneCallbackList() {
            if (doneCallbackList != null) {
                DoneCallback task;
                while ((task = doneCallbackList.poll()) != null) {
                    try {
                        task.onComplete(new ClientDisconnectedException());
                    } catch (Throwable throwable) {
                        log.debug("Exception clearing done callback", throwable);
                    }
                }
            }
        }

        @Override
        public void onWriteable() {
            DoneCallback task;
            while (request.ctx.channel().isWritable() && doneCallbackList != null && (task = doneCallbackList.poll()) != null) {
                try {
                    task.onComplete(null);
                } catch (Throwable throwable) {
                    log.debug("Exception on completing task", throwable);
                }
            }
        }

        @Override
        public void complete() {
            httpExchange.complete(false);
        }

        @Override
        public void complete(Throwable throwable) {
            if (throwable == null) {
                complete();
            } else {
                boolean forceDisconnect = true;
                try {
                    forceDisconnect = dealWithUnhandledException(request, httpExchange.response, throwable);
                } finally {
                    httpExchange.complete(forceDisconnect);
                }
            }
        }

        @Override
        public void write(ByteBuffer data, DoneCallback callback) {

            ChannelFuture writeFuture = (ChannelFuture) write(data);
            writeFuture.addListener(future -> {
                /*
                 * The DoneCallback are commonly used to trigger writing more data into the target channel,
                 * so we delay the done callback invocation till the target netty channel become writable,
                 * in this way we prevent OOM for fast producer / slow consumer scenario.
                 *
                 * We use a doneCallbackList here to make sure the done callback being invoked in the same
                 * order as it come in. the doneCallbackList operation are all within same netty event loop thread,
                 * so we LinkedList rather than ConcurrentQueue for it.
                 *
                 * Threading related:
                 * 1. (ChannelFuture) write(data) run in mu-server thread.
                 * 2. callback in "writeFuture.addListener(callback)" run the netty event loop thread.
                 *
                 */
                try {
                    if (!future.isSuccess()) {
                        callback.onComplete(future.cause());
                    } else if (!isConnectionStateSupported) {
                        // http 2 not support DoneCallback delay at the moment
                        callback.onComplete(null);
                    } else if (request.ctx.channel().isWritable() && (doneCallbackList == null || doneCallbackList.isEmpty())) {
                        callback.onComplete(null);
                    } else {
                        if (doneCallbackList == null) {
                            doneCallbackList = new LinkedList<>();
                        }
                        doneCallbackList.add(callback);
                    }
                } catch (Throwable e) {
                    log.warn("Unhandled exception from write callback", e);
                    callback.onComplete(e);
                }
            });
        }

        @Override
        @Deprecated
        public void write(ByteBuffer data, WriteCallback callback) {
            write(data, error -> {
                if (error == null) {
                    callback.onSuccess();
                } else {
                    callback.onFailure(error);
                }
            });
        }

        @Override
        public Future<Void> write(ByteBuffer data) {
            NettyResponseAdaptor response = request.httpExchange.response;
            try {
                return response.write(data);
            } catch (Throwable e) {
                return request.ctx.channel().newFailedFuture(e);
            }
        }

        @Override
        @Deprecated
        public void setResponseCompleteHandler(ResponseCompleteListener responseCompleteListener) {
            if (responseCompleteListener != null) {
                addResponseCompleteHandler(responseCompleteListener);
            }
        }

        @Override
        public void addResponseCompleteHandler(ResponseCompleteListener responseCompleteListener) {
            this.httpExchange.response.addChangeListener((exchange, newState) -> {
                if (newState.endState()) {
                    responseCompleteListener.onComplete(exchange);
                }
            });
        }

        @Override
        @Deprecated
        public void setResponseCompletedHandler(ResponseCompletedListener responseCompletedListener) {
            if (responseCompletedListener != null) {
                addResponseCompleteHandler(info -> responseCompletedListener.onComplete(info.completedSuccessfully()));
            }
        }

        void onCancelled(ResponseState reason) {
            this.clearDoneCallbackList();
        }

    }

    private static class StringRequestBodyReader implements RequestBodyReader {
        private final Charset bodyCharset;
        private final StringBuilder sb;
        private final CompletableFuture<String> future = new CompletableFuture<>();

        public StringRequestBodyReader(Charset bodyCharset, int sizeInBytes) {
            this.bodyCharset = bodyCharset;
            if (sizeInBytes > 0) {
                sb = new StringBuilder(sizeInBytes); // not necessarily the size in characters, but a good enough estimate
            } else {
                sb = new StringBuilder();
            }
        }

        @Override
        public void onRequestBodyRead(ByteBuf content, boolean last, DoneCallback callback) {
            try {
                String toAdd = content.toString(bodyCharset);
                sb.append(toAdd);
                if (last) {
                    future.complete(sb.toString());
                }
                callback.onComplete(null);
            } catch (Exception e) {
                try {
                    callback.onComplete(e);
                } catch (Exception ignored) {
                }
            }
        }

        public CompletableFuture<String> future() {
            return future;
        }
    }

}

