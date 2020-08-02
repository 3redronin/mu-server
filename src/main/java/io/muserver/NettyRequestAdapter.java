package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
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

import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.muserver.Cookie.nettyToMu;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;

class NettyRequestAdapter implements MuRequest {
    private static final Logger log = LoggerFactory.getLogger(NettyRequestAdapter.class);
    private final ChannelHandlerContext ctx;
    private final Channel channel;
    private final HttpRequest request;
    private final MuServer server;
    private final URI serverUri;
    private final URI uri;
    private final Method method;
    private final Headers headers;
    AsyncContext nettyAsyncContext;
    private GrowableByteBufferInputStream inputStream;
    private final RequestParameters query;
    private RequestParameters form;
    private boolean bodyRead = false;
    private List<Cookie> cookies;
    private String contextPath = "";
    private String relativePath;
    private HttpPostMultipartRequestDecoder multipartRequestDecoder;
    private HashMap<String, List<UploadedFile>> uploads;
    private Map<String, Object> attributes;
    private volatile AsyncHandleImpl asyncHandle;
    private final boolean keepalive;
    private final String protocol;
    private final long startTime = System.currentTimeMillis();
    private final HttpConnection connection;

    NettyRequestAdapter(ChannelHandlerContext ctx, Channel channel, HttpRequest request, Headers headers, MuServer server, Method method, String proto, String uri, boolean keepalive, String host, String protocol, HttpConnection connection) {
        this.ctx = ctx;
        this.channel = channel;
        this.request = request;
        this.server = server;
        this.keepalive = keepalive;
        this.protocol = protocol;
        this.connection = connection;
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
        return protocol;
    }

    @Override
    public HttpConnection connection() {
        return this.connection;
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
        return this.startTime;
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
        if (inputStream == null) {
            return Optional.empty();
        } else {
            claimingBodyRead();
            return Optional.of(inputStream);
        }
    }

    private byte[] readBodyAsBytes() throws IOException {
        if (inputStream != null) {
            claimingBodyRead();
            return Mutils.toByteArray(inputStream, 2048);
        } else {
            return new byte[0];
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
        byte[] bytes = readBodyAsBytes();
        return new String(bytes, bodyCharset);
    }

    private void claimingBodyRead() {
        if (bodyRead) {
            throw new IllegalStateException("The body of the request message cannot be read twice. This can happen when calling any 2 of inputStream(), readBodyAsString(), or form() methods.");
        }
        bodyRead = true;
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

    public List<String> parameters(String name) {
        return query.getAll(name);
    }

    public String formValue(String name) throws IOException {
        return form().get(name);
    }


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
    public Object state() {
        return attribute("_value_");
    }

    @Override
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
            throw new IllegalStateException("handleAsync called twice for " + this);
        }
        asyncHandle = new AsyncHandleImpl(this, nettyAsyncContext);
        return asyncHandle;
    }

    @Override
    public String remoteAddress() {
        return connection().remoteAddress().getHostString();
    }

    @Override
    public MuServer server() {
        return server;
    }

    private void ensureFormDataLoaded() throws IOException {
        if (form == null) {
            if (contentType().startsWith("multipart/")) {
                multipartRequestDecoder = new HttpPostMultipartRequestDecoder(request);
                if (inputStream != null) {
                    claimingBodyRead();

                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = inputStream.read(buffer)) > -1) {
                        if (read > 0) {
                            ByteBuf content = Unpooled.copiedBuffer(buffer, 0, read);
                            multipartRequestDecoder.offer(new DefaultHttpContent(content));
                        }
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

    void inputStream(GrowableByteBufferInputStream stream) {
        this.inputStream = stream;
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

    void onCancelled() {
        if (asyncHandle != null) {
            asyncHandle.onClientDisconnected();
        }
    }

    boolean websocketUpgrade(MuWebSocket muWebSocket, HttpHeaders responseHeaders, long idleReadTimeoutMills, long pingAfterWriteMillis, int maxFramePayloadLength) throws IOException {
        String url = "ws" + uri().toString().substring(4);
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(url, null, false, maxFramePayloadLength);

        if (inputStream().isPresent()) {
            try (InputStream is = inputStream().get()) {
                byte[] buffer = new byte[8192];
                while (is.read(buffer) > -1) {
                    // there shouldn't be a body, but just consume and discard if there is
                }
            }
        }

        DefaultFullHttpRequest fullReq = new DefaultFullHttpRequest(request.protocolVersion(), request.method(), request.uri(), new EmptyByteBuf(ByteBufAllocator.DEFAULT), request.headers(), EmptyHttpHeaders.INSTANCE);
        WebSocketServerHandshaker handshaker = factory.newHandshaker(fullReq);
        if (handshaker == null) {
            throw new UnsupportedOperationException();
        }

        ctx.channel().pipeline().replace("idle", "idle",
            new IdleStateHandler(idleReadTimeoutMills, pingAfterWriteMillis, 0, TimeUnit.MILLISECONDS));
        MuWebSocketSessionImpl session = new MuWebSocketSessionImpl(ctx, muWebSocket);
        Http1Connection.setAsyncContext(ctx, null);
        ctx.channel().attr(Http1Connection.WEBSOCKET_ATTRIBUTE).set(session);
        handshaker.handshake(ctx.channel(), fullReq, responseHeaders, ctx.channel().newPromise())
            .addListener(future -> {
                if (future.isSuccess()) {
                    muWebSocket.onConnect(session);
                    ctx.channel().read();
                } else {
                    ctx.channel().close();
                }
            });

        return true;
    }


    private static class AsyncHandleImpl implements AsyncHandle, ConnectionState.Listener {

        private final boolean isConnectionStateSupported;
        private final NettyRequestAdapter request;
        private final AsyncContext asyncContext;
        private volatile ResponseCompleteListener responseCompleteListener;
        private LinkedList<DoneCallback> doneCallbackList;

        private AsyncHandleImpl(NettyRequestAdapter request, AsyncContext asyncContext) {
            this.request = request;
            this.asyncContext = asyncContext;
            this.doneCallbackList = new LinkedList<>();
            this.isConnectionStateSupported = request.connection instanceof ConnectionState;
            if (isConnectionStateSupported) {
                ((ConnectionState) request.connection).registerConnectionStateListener(this);
                log.warn("J:registerConnectionStateListener");
            }
        }

        @Override
        public void setReadListener(RequestBodyListener readListener) {
            request.claimingBodyRead();
            if (readListener != null) {
                if (request.inputStream == null) {
                    readListener.onComplete();
                } else {
                    request.inputStream.switchToListener(readListener);
                }
            }
        }

        private void clearDoneCallbackList() {
            DoneCallback task;
            while ((task = doneCallbackList.poll()) != null) {
                try {
                    task.onComplete(new ClientDisconnectedException());
                } catch (Throwable throwable) {
                    log.debug("Exception clearing done callback", throwable);
                }
            }
        }

        @Override
        public void onWriteable() throws Exception {
            DoneCallback task;

            if (!request.channel.isActive()) {
                clearDoneCallbackList();
                return;
            }

            while (request.channel.isWritable() && (task = doneCallbackList.poll()) != null) {
                try {
                    task.onComplete(null);
                } catch (Throwable throwable) {
                    log.debug("Exception on completing task", throwable);
                }
            }
        }

        @Override
        public void onConnectionClose() throws Exception {
            this.clearDoneCallbackList();
        }

        @Override
        public void complete() {
            request.nettyAsyncContext.complete(false);
            raiseResponseComplete();
        }

        @Override
        public void complete(Throwable throwable) {
            if (throwable == null) {
                complete();
            } else {
                boolean forceDisconnect = true;
                try {
                    forceDisconnect = NettyHandlerAdapter.dealWithUnhandledException(request, request.nettyAsyncContext.response, throwable);
                } finally {
                    request.nettyAsyncContext.complete(forceDisconnect);
                    raiseResponseComplete();
                }
            }
        }

        void raiseResponseComplete() {
            ResponseCompleteListener listener = this.responseCompleteListener;
            if (listener != null) {
                listener.onComplete(asyncContext);
                this.responseCompleteListener = null;
            }
        }

        @Override
        public void write(ByteBuffer data, DoneCallback callback) {
            log.info("write: isWritable={}, listSize={}, byteBeforeWritable={}",
                request.channel.isWritable(), doneCallbackList.size(), request.channel.bytesBeforeWritable());

            ChannelFuture writeFuture = (ChannelFuture) write(data);
            writeFuture.addListener(future -> {

                log.info("callback: isSuccess={}, isWritable={}, listSize={}, byteBeforeWritable={}",
                    future.isSuccess(), request.channel.isWritable(), doneCallbackList.size(), request.channel.bytesBeforeWritable());

                /**
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
                    } else if (request.channel.isWritable() && doneCallbackList.size() == 0) {
                        callback.onComplete(null);
                    } else {
                        doneCallbackList.add(callback);
                    }
                } catch (Throwable e) {
                    log.warn("Unhandled exception from write callback", e);
                    complete(e);
                }
            });
        }

        @Override
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
            NettyResponseAdaptor response = (NettyResponseAdaptor) request.nettyAsyncContext.response;
            try {
                return response.write(data);
            } catch (Throwable e) {
                return request.channel.newFailedFuture(e);
            }
        }

        @Override
        public void setResponseCompleteHandler(ResponseCompleteListener responseCompleteListener) {
            this.responseCompleteListener = responseCompleteListener;

        }

        @Override
        public void setResponseCompletedHandler(ResponseCompletedListener responseCompletedListener) {
            if (responseCompletedListener == null) {
                this.responseCompleteListener = null;
            } else {
                this.responseCompleteListener = info -> responseCompletedListener.onComplete(info.completedSuccessfully());
            }
        }

        void onClientDisconnected() {
            ResponseCompleteListener listener = this.responseCompleteListener;
            if (listener != null) {
                listener.onComplete(asyncContext);
            }
        }
    }

}
