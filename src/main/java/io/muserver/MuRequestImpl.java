package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;


public class MuRequestImpl implements MuRequest {
    private static final Logger log = LoggerFactory.getLogger(MuRequestImpl.class);

    private final long startTime = System.currentTimeMillis();
    final MuExchangeData data;
    private final Method method;
    private final URI uri;
    private final URI serverUri;
    private RequestState state;
    private String contextPath = "";
    private String relativePath;
    private final Headers headers;
    private final boolean hasBody;
    private Headers trailers;
    private List<Cookie> cookies;
    private QueryString query;

    public MuRequestImpl(MuExchangeData data, Method method, String relativeUri, Headers headers, boolean hasBody) {
        this.data = data;
        this.method = method;
        this.serverUri = data.connection.serverUri().resolve(relativeUri);
        this.relativePath = serverUri.getRawPath();
        var host = headers.get("host");
        this.uri = getUri(headers, serverUri.getScheme(), host, relativeUri, serverUri);
        this.headers = headers;
        this.hasBody = hasBody;
        this.state = hasBody ? RequestState.RECEIVING_BODY : RequestState.COMPLETE;
    }

    private static URI getUri(Headers h, String scheme, String hostHeader, String requestUri, URI defaultValue) {
        try {
            List<ForwardedHeader> forwarded = h.forwarded();
            if (forwarded.isEmpty()) {
                return defaultValue;
            }
            ForwardedHeader f = forwarded.get(0);
            String originalScheme = Mutils.coalesce(f.proto(), scheme);
            String host = Mutils.coalesce(f.host(), hostHeader, "localhost");
            return URI.create(originalScheme + "://" + host).resolve(requestUri);
        } catch (Exception e) {
            log.warn("Could not create a URI object using header values " + h
                + " so using local server URI. URL generation (including in redirects) may be incorrect.");
            return defaultValue;
        }
    }


    public RequestState requestState() {
        return state;
    }

    @Override
    public boolean hasBody() {
        return hasBody;
    }

    @Override
    public String contentType() {
        return null;
    }

    @Override
    public long startTime() {
        return startTime;
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

    @Override
    public Optional<InputStream> inputStream() {
        return Optional.empty();
    }

    @Override
    public String readBodyAsString() throws IOException {
        return null;
    }

    @Override
    public List<UploadedFile> uploadedFiles(String name) throws IOException {
        return null;
    }

    @Override
    public UploadedFile uploadedFile(String name) throws IOException {
        return null;
    }

    @Override
    public RequestParameters query() {
        if (this.query == null) {
            this.query = QueryString.parse(serverUri.getRawQuery());
        }
        return query;
    }

    @Override
    public RequestParameters form() throws IOException {
        return null;
    }

    @Override
    public List<Cookie> cookies() {
        if (this.cookies == null) {
            cookies = headers.cookies();
        }
        return this.cookies;
    }

    @Override
    public Optional<String> cookie(String name) {
        return cookies().stream().filter(c -> c.name().equals(name)).map(Cookie::value).findFirst();
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
        return null;
    }

    @Override
    public void attribute(String key, Object value) {

    }

    @Override
    public Map<String, Object> attributes() {
        return null;
    }

    @Override
    public AsyncHandle handleAsync() {
        return null;
    }

    @Override
    public String remoteAddress() {
        return null;
    }

    @Override
    public String clientIP() {
        return null;
    }

    @Override
    public MuServer server() {
        return data.acceptor().muServer;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public String protocol() {
        return httpVersion().version();
    }

    @Override
    public HttpVersion httpVersion() {
        return data.newRequest.version();
    }

    @Override
    public HttpConnection connection() {
        return data.connection;
    }

    @Override
    public Headers trailers() {
        return trailers;
    }

    @Override
    public String toString() {
        return "MuRequest " + data.newRequest.version().version() + " " + method + " " + serverUri;
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

    void onComplete(Headers trailers) {
        state = RequestState.COMPLETE;
        this.trailers = trailers;
    }
    void onError() {
        this.state = RequestState.ERRORED;
    }

    public void onCancelled(ResponseState responseState, Throwable cause) {
        if (!state.endState()) {
            // todo: what to do with reader / thread that is blocking
            state = RequestState.ERRORED;
        }
    }
}
