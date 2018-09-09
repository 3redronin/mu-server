package io.muserver;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.QueryStringEncoder;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.muserver.Cookie.nettyToMu;
import static io.muserver.Mutils.coalesce;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

class MuRequestImpl implements MuRequest {
    private static final Logger log = LoggerFactory.getLogger(MuRequestImpl.class);

    private final URI serverUri;
    private final URI uri;
    private String relativePath;
    private final NettyRequestParameters query;
    private final Method method;
    private final MuHeaders headers;
    private final ClientConnection clientConnection;
    private final GrowableByteBufferInputStream inputStream;
    private RequestParameters form;
    private boolean bodyRead = false;
    private Set<Cookie> cookies;
    private String contextPath = "";
    private MultipartRequestBodyParser multipartRequestDecoder;
    private HashMap<String, List<UploadedFile>> uploads;
    private Object state;
    private volatile AsyncHandle asyncHandle;
    private boolean isAsync = false;


    MuRequestImpl(Method method, URI requestUri, MuHeaders headers, GrowableByteBufferInputStream body, ClientConnection clientConnection) {
        this.headers = headers;
        this.clientConnection = clientConnection;
        String protocol = clientConnection.protocol;
        String host = headers.get(HeaderNames.HOST);
        this.serverUri = URI.create(protocol + "://" + host + requestUri);
        this.uri = getUri(headers, protocol, host, requestUri, serverUri);
        this.relativePath = requestUri.getRawPath();
        this.query = new NettyRequestParameters(new QueryStringDecoder(requestUri));
        this.method = method;
        this.inputStream = body;
    }

    @Override
    public MediaType contentType() {
        String c = headers.get(HttpHeaderNames.CONTENT_TYPE);
        if (c == null) return null;
        return MediaTypeParser.fromString(c);
    }

    @Override
    public Method method() {
        return method;
    }

    @Override
    public URI uri() {
        return uri;
    }

    @Override
    public URI serverURI() {
        return serverUri;
    }

    @Override
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
            return Mutils.toByteArray(inputStream, 8192);
        } else {
            return new byte[0];
        }
    }


    public String readBodyAsString() throws IOException {
        String charset = contentType().getParameters().get("charset");
        return new String(readBodyAsBytes(), coalesce(charset, "UTF-8"));
    }

    private void claimingBodyRead() {
        if (bodyRead) {
            throw new IllegalStateException("The body of the request message cannot be read twice. This can happen when calling any 2 of inputStream(), readBodyAsString(), or form() methods.");
        }
        bodyRead = true;
    }

    private static URI getUri(MuHeaders h, String scheme, String hostHeader, URI requestUri, URI serverUri) {
        String xforwardedProto = h.get(HeaderNames.X_FORWARDED_PROTO, scheme);
        String xforwardedHost = h.get(HeaderNames.X_FORWARDED_HOST);
        try {
            String host, portFromHost = "-1";
            if(xforwardedHost != null) {
                int ipv6CheckAndIndex = xforwardedHost.lastIndexOf("]");
                int lastColonCheckAndIndex = xforwardedHost.lastIndexOf(":");
                if (ipv6CheckAndIndex != -1) {      // IPv6
                    host = xforwardedHost.substring(0, ipv6CheckAndIndex + 1);
                    if (ipv6CheckAndIndex < lastColonCheckAndIndex) { // has port
                        portFromHost = xforwardedHost.substring(lastColonCheckAndIndex + 1);
                    }
                } else if(lastColonCheckAndIndex != -1) { // IPv4 or domain and has port
                    host = xforwardedHost.substring(0, lastColonCheckAndIndex);
                    portFromHost = xforwardedHost.substring(lastColonCheckAndIndex + 1);
                } else {  // no port
                    host = xforwardedHost;
                }
            } else {
                host = hostHeader;
            }
            int port = h.getInt(HeaderNames.X_FORWARDED_PORT, xforwardedHost != null ? Integer.valueOf(portFromHost) : -1);
            String portStr = (port != 80 && port != 443 && port > 0) ? ":" + port : "";
            return new URI(xforwardedProto + "://" + host + portStr + requestUri);
        } catch (URISyntaxException e) {
            log.warn("Could not create a URI object using X-Forwarded values " + xforwardedProto + " and " + xforwardedHost
                + " so using local server URI. URL generation (including in redirects) may be incorrect.");
            return serverUri;
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


    boolean isAsync() {
        return this.isAsync;
    }

    @Override
    public AsyncHandle handleAsync() {
        if (isAsync()) {
            throw new IllegalStateException("handleAsync() called twice for " + this);
        }
        this.isAsync = true;
        return asyncHandle;
    }

    @Override
    public String remoteAddress() {
        return clientConnection.clientAddress.getHostAddress();
    }

    @Override
    public MuServer server() {
        return clientConnection.server;
    }

    private void ensureFormDataLoaded() throws IOException {
        if (form == null) {
            if (contentType().getType().equals("multipart")) {

                System.out.println("headers = " + headers);
                String s = new String(readBodyAsBytes(), StandardCharsets.UTF_8);
                System.out.println("s = " + "***" + s + "***");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                Map<String, String> contentParam = contentType().getParameters();
                Charset bodyCharset = Charset.forName(contentParam.getOrDefault("charset", "UTF-8"));
                String boundary = contentParam.get("boundary");
                if (boundary == null || boundary.isEmpty() || boundary.length() > 70) {
                    throw new MuException("Invalid boundary in multipart form - must be between 1 an 70 characters");
                }
                multipartRequestDecoder = new MultipartRequestBodyParser(null, bodyCharset, boundary);
                if (inputStream != null) {
                    claimingBodyRead();
                    multipartRequestDecoder.parse(inputStream);
                }
                uploads = new HashMap<>();

                List<InterfaceHttpData> bodyHttpDatas = null;
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

    void setReadListener(RequestBodyListener readListener) {
        claimingBodyRead();
        if (readListener != null) {
            if (inputStream == null) {
                readListener.onComplete();
            } else {
                inputStream.switchToListener(readListener);
            }
        }
    }

    void setAsyncHandle(AsyncHandle asyncHandle) {
        this.asyncHandle = asyncHandle;
    }
}
