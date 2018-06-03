package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

import static io.muserver.Cookie.nettyToMu;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

class NettyRequestAdapter implements MuRequest {
    private static final Logger log = LoggerFactory.getLogger(NettyRequestAdapter.class);
    private final HttpRequest request;
    private final URI serverUri;
    private final URI uri;
    private final QueryStringDecoder queryStringDecoder;
    private final Method method;
    private final Headers headers;
    private InputStream inputStream;
    private QueryStringDecoder formDecoder;
    private boolean bodyRead = false;
    private Set<Cookie> cookies;
    private String contextPath = "";
    private String relativePath;
    private HttpPostMultipartRequestDecoder multipartRequestDecoder;
    private HashMap<String, List<UploadedFile>> uploads;
    private Object state;

    NettyRequestAdapter(String proto, HttpRequest request) {
        this.request = request;
        this.serverUri = URI.create(proto + "://" + request.headers().get(HeaderNames.HOST) + request.uri());
        this.uri = getUri(request, serverUri);
        this.relativePath = this.uri.getRawPath();
        this.queryStringDecoder = new QueryStringDecoder(request.uri(), true);
        this.method = Method.fromNetty(request.method());
        this.headers = new Headers(request.headers());
    }

    boolean isKeepAliveRequested() {
        return HttpUtil.isKeepAlive(request);
    }

    private static URI getUri(HttpRequest request, URI serverUri) {
        HttpHeaders h = request.headers();
        String proto = h.get(HeaderNames.X_FORWARDED_PROTO, serverUri.getScheme());
        String host = h.get(HeaderNames.X_FORWARDED_HOST, serverUri.getHost());
        int port = h.getInt(HeaderNames.X_FORWARDED_PORT, serverUri.getPort());
        port = (port != 80 && port != 443 && port > 0) ? port : -1;
        try {
            return new URI(proto, serverUri.getUserInfo(), host, port, serverUri.getPath(), serverUri.getQuery(), serverUri.getFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Could not convert " + request.uri() + " into a URI object", e);
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
            throw new IllegalStateException("The body of the request message cannot be read twice. This can happen when calling any 2 of inputStream(), readBodyAsString(), or getFormValue() methods.");
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
        return getSingleParam(name, queryStringDecoder);
    }

    private static String getSingleParam(String name, QueryStringDecoder queryStringDecoder) {
        List<String> values = queryStringDecoder.parameters().get(name);
        if (values == null) {
            return "";
        }
        return values.get(0);
    }


    public List<String> parameters(String name) {
        return getMultipleParams(name, queryStringDecoder);
    }

    private static List<String> getMultipleParams(String name, QueryStringDecoder queryStringDecoder) {
        List<String> values = queryStringDecoder.parameters().get(name);
        if (values == null) {
            return emptyList();
        }
        return values;
    }


    public String formValue(String name) throws IOException {
        ensureFormDataLoaded();
        return getSingleParam(name, formDecoder);
    }


    public List<String> formValues(String name) throws IOException {
        ensureFormDataLoaded();
        return getMultipleParams(name, formDecoder);
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

    private void ensureFormDataLoaded() throws IOException {
        if (formDecoder == null) {
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
                formDecoder = new QueryStringDecoder(qse.toString());
            } else {
                String body = readBodyAsString();
                formDecoder = new QueryStringDecoder(body, false);
            }
        }
    }

    void inputStream(InputStream stream) {
        this.inputStream = stream;
    }

    public String toString() {
        return method().name() + " " + uri();
    }

    public void addContext(String contextToAdd) {
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
            multipartRequestDecoder.destroy();
        }
    }
}
