package io.muserver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MuRequestImpl implements MuRequest {

    private final long startTime = System.currentTimeMillis();
    private final MuExchangeData data;
    private final Method method;
    private final URI uri;
    private final URI serverUri;
    private RequestState state = RequestState.HEADERS_RECEIVED;
    private String contextPath = "";
    private String relativePath;
    private final Headers headers;
    private Headers trailers;

    public MuRequestImpl(MuExchangeData data, Method method, URI uri, URI serverUri, Headers headers) {
        this.data = data;
        this.method = method;
        this.uri = uri;
        this.relativePath = serverUri.getRawPath();
        this.serverUri = serverUri;
        this.headers = headers;
    }

    public RequestState requestState() {
        return state;
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
        return null;
    }

    @Override
    public RequestParameters form() throws IOException {
        return null;
    }

    @Override
    public List<Cookie> cookies() {
        return null;
    }

    @Override
    public Optional<String> cookie(String name) {
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
        return data.server;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public String protocol() {
        return data.httpVersion.version();
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
        return "MuRequest " + data.httpVersion.version() + " " + method + " " + serverUri;
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

}
