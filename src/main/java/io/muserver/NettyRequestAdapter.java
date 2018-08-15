package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.Cookie.nettyToMu;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

class NettyRequestAdapter implements MuRequest {
    private static final Logger log = LoggerFactory.getLogger(NettyRequestAdapter.class);
    private final Channel channel;
    private final HttpRequest request;
    private final AtomicReference<MuServer> serverRef;
    private final URI serverUri;
    private final URI uri;
    private final Method method;
    private final Headers headers;
    AsyncContext nettyAsyncContext;
    private GrowableByteBufferInputStream inputStream;
    private final RequestParameters query;
    private RequestParameters form;
    private boolean bodyRead = false;
    private Set<Cookie> cookies;
    private String contextPath = "";
    private String relativePath;
    private HttpPostMultipartRequestDecoder multipartRequestDecoder;
    private HashMap<String, List<UploadedFile>> uploads;
    private Object state;
    private volatile AsyncHandleImpl asyncHandle;

    NettyRequestAdapter(Channel channel, HttpRequest request, AtomicReference<MuServer> serverRef, Method method, String protocol) {
        this.channel = channel;
        this.request = request;
        this.serverRef = serverRef;
        this.serverUri = URI.create(protocol + "://" + request.headers().get(HeaderNames.HOST) + request.uri());
        this.uri = getUri(request, serverUri);
        this.relativePath = this.uri.getRawPath();
        this.query = new NettyRequestParameters(new QueryStringDecoder(request.uri(), true));
        this.method = method;
        this.headers = new Headers(request.headers());
    }

    boolean isAsync() {
        return asyncHandle != null;
    }

    boolean isKeepAliveRequested() {
        return HttpUtil.isKeepAlive(request);
    }

    private static URI getUri(HttpRequest request, URI serverUri) {
        HttpHeaders h = request.headers();
        String proto = h.get(HeaderNames.X_FORWARDED_PROTO, serverUri.getScheme());
        String xforwardedHost = h.get(HeaderNames.X_FORWARDED_HOST);
        try {
            String host, portFromHost = "-1";
            if(xforwardedHost != null) {
                int ipv6CheckAndIndex = xforwardedHost.lastIndexOf("]");
                int lastColonCheckAndIndex = xforwardedHost.lastIndexOf(":");
                if (ipv6CheckAndIndex != -1) {      // IPv6
                    host = xforwardedHost.substring(0, ipv6CheckAndIndex + 1);
                    if (ipv6CheckAndIndex < lastColonCheckAndIndex) { // has port
                        portFromHost = xforwardedHost.substring(lastColonCheckAndIndex + 1, xforwardedHost.length());
                    }
                } else if(lastColonCheckAndIndex != -1) { // IPv4 or domain and has port
                    host = xforwardedHost.substring(0, lastColonCheckAndIndex);
                    portFromHost = xforwardedHost.substring(lastColonCheckAndIndex + 1, xforwardedHost.length());
                } else {  // no port
                    host = xforwardedHost;
                }
            } else {
                host = serverUri.getHost();
            }
            int port = h.getInt(HeaderNames.X_FORWARDED_PORT, xforwardedHost != null ? Integer.valueOf(portFromHost) : serverUri.getPort());
            port = (port != 80 && port != 443 && port > 0) ? port : -1;
            return new URI(proto, serverUri.getUserInfo(), host, port, serverUri.getPath(), serverUri.getQuery(), serverUri.getFragment());
        } catch (URISyntaxException e) {
            log.warn("Could not convert " + request.uri() + " into a URI object using X-Forwarded values " + proto + " and " + xforwardedHost
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
        return new String(readBodyAsBytes(), UTF_8); // TODO: respect the charset of the content-type if provided
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
    public Set<Cookie> cookies() {
        if (this.cookies == null) {
            String encoded = headers().get(HeaderNames.COOKIE);
            if (encoded == null) {
                this.cookies = emptySet();
            } else {
                this.cookies = nettyToMu(ServerCookieDecoder.STRICT.decode(encoded));
            }
        }
        return this.cookies;
    }

    @Override
    public Optional<String> cookie(String name) {
        Set<Cookie> cookies = cookies();
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
        return state;
    }

    @Override
    public void state(Object value) {
        this.state = value;
    }

    @Override
    public AsyncHandle handleAsync() {
        if (isAsync()) {
            throw new IllegalStateException("handleAsync called twice for " + this);
        }
        asyncHandle = new AsyncHandleImpl(this);
        return asyncHandle;
    }

    @Override
    public String remoteAddress() {
        return ((InetSocketAddress)channel.remoteAddress()).getAddress().getHostAddress();
    }

    @Override
    public MuServer server() {
        return serverRef.get();
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
                        UploadedFile uploadedFile = new MuUploadedFile(fileUpload);
                        addFile(fileUpload.getName(), uploadedFile);
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
        if (contextToAdd.endsWith("/")) {
            contextToAdd = contextToAdd.substring(0, contextToAdd.length() - 1);
        }
        if (!contextToAdd.startsWith("/")) {
            contextToAdd = "/" + contextToAdd;
        }
        this.contextPath = this.contextPath + contextToAdd;
        this.relativePath = this.relativePath.substring(contextToAdd.length());
    }

    void clean() {
        state(null);
        if (multipartRequestDecoder != null) {
            // need to clear the datas before destroying. See https://github.com/netty/netty/issues/7814#issuecomment-397855311
            multipartRequestDecoder.getBodyHttpDatas().clear();
            multipartRequestDecoder.destroy();
            multipartRequestDecoder = null;
        }
    }

    public void onClientDisconnected(boolean complete) {
        if (asyncHandle != null) {
            asyncHandle.onClientDisconnected(complete);
        }
    }

    private static class AsyncHandleImpl implements AsyncHandle {

        private final NettyRequestAdapter request;
        private ResponseCompletedListener responseCompletedListener;

        private AsyncHandleImpl(NettyRequestAdapter request) {
            this.request = request;
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

        @Override
        public void complete() {
            request.nettyAsyncContext.complete(false);
        }

        @Override
        public void complete(Throwable throwable) {
            try {
                MuServerHandler.dealWithUnhandledException(request, request.nettyAsyncContext.response, throwable);
            } finally {
                request.nettyAsyncContext.complete(true);
            }
        }

        @Override
        public void write(ByteBuffer data, WriteCallback callback) {
            try {
                ChannelFuture writeFuture = (ChannelFuture) write(data);
                writeFuture.addListener(future -> {
                    try {
                        if (future.isSuccess()) {
                            callback.onSuccess();
                        } else {
                            callback.onFailure(future.cause());
                        }
                    } catch (Exception e) {
                        log.warn("Unhandled exception from write callback", e);
                        complete(e);
                    }
                });
            } catch (Exception e) {
                try {
                    callback.onFailure(e);
                } catch (Exception e1) {
                    log.warn("Unhandled exception from write callback", e1);
                    complete(e);
                }
            }
        }

        @Override
        public Future<Void> write(ByteBuffer data) {
            NettyResponseAdaptor response = (NettyResponseAdaptor) request.nettyAsyncContext.response;
            return response.write(data);
        }

        @Override
        public void setResponseCompletedHandler(ResponseCompletedListener responseCompletedListener) {
            this.responseCompletedListener = responseCompletedListener;
        }

        void onClientDisconnected(boolean complete) {
            ResponseCompletedListener listener = this.responseCompletedListener;
            if (listener != null) {
                listener.onComplete(complete);
            }

        }
    }
}
