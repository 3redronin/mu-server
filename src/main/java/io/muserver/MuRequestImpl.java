package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


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
    private Map<String, Object> attributes;
    private CompletableFuture<MuForm> formFuture;

    public MuRequestImpl(MuExchangeData data, Method method, String relativeUri, Headers headers, boolean hasBody) {
        this.data = data;
        this.method = method;



        this.serverUri = data.connection.serverUri().resolve(relativeUri);
        this.relativePath = serverUri.getRawPath();
        var host = headers.get("host");
        this.uri = getUri(headers, serverUri.getScheme(), host, relativeUri, serverUri);
        this.headers = headers;
        this.hasBody = hasBody;
        this.state = hasBody ? RequestState.HEADERS_RECEIVED : RequestState.COMPLETE;
    }

    private static URI getUri(Headers h, String scheme, String hostHeader, String requestUri, URI defaultValue) {
        try {
            List<ForwardedHeader> forwarded = h.forwarded();
            if (forwarded.isEmpty()) {
                if (Mutils.nullOrEmpty(hostHeader) || defaultValue.getHost().equals(hostHeader)) {
                    return defaultValue;
                }
                return URI.create(scheme + "://" + hostHeader).resolve(requestUri);
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
        return hasBody ? Optional.of(data.exchange.requestInputStream()) : Optional.empty();
    }

    @Override
    public String readBodyAsString() throws IOException {
        if (data.exchange.requestBodyListener() != null) {
            throw new IllegalStateException("The body of the request message cannot be read twice. This can happen when calling any 2 of inputStream(), readBodyAsString(), or form() methods.");
        }
        Optional<InputStream> inputStream = inputStream();
        if (inputStream.isEmpty()) return "";
        Charset charset = headers.contentCharset(true);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long byteLen = headers.getLong(HeaderNames.CONTENT_LENGTH.toString(), Long.MAX_VALUE);
        Mutils.copy(inputStream.get(), baos, (int)Math.min(byteLen, 8192L)); // todo use read buffer size
        return baos.toString(charset);
    }

    @Override
    public List<UploadedFile> uploadedFiles(String name) throws IOException {
        return form().uploadedFiles(name);
    }

    @Override
    public UploadedFile uploadedFile(String name) throws IOException {
        return form().uploadedFile(name);
    }

    @Override
    public RequestParameters query() {
        if (this.query == null) {
            this.query = QueryString.parse(serverUri.getRawQuery());
        }
        return query;
    }

    @Override
    public MuForm form() throws IOException {
        if (isAsync()) {
            throw new IllegalStateException("Use AsyncHandle.readForm to read form bodies in async mode");
        }
        if (formFuture == null) {
            formFuture = new CompletableFuture<>();
            data.exchange.readForm(new FormConsumer() {
                @Override
                public void onReady(MuForm form) {
                    formFuture.complete(form);
                }
                @Override
                public void onError(Throwable cause) {
                    formFuture.completeExceptionally(cause);
                }
            });
        }
        try {
            return formFuture.get(data.server().settings.requestReadTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new InterruptedIOException("Interrupted while reading form");
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof IOException ioe) throw ioe;
            throw new IOException("Error while reading form body", cause);
        } catch (TimeoutException e) {
            throw new IOException("Timed out while reading form body", e);
        }
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
        return data.exchange.handleAsync();
    }

    @Override
    public String remoteAddress() {
        return data.connection.remoteAddress().getHostString();
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
        return data.exchange.isAsync();
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

    void onRequestBodyReceived() {
        if (state == RequestState.HEADERS_RECEIVED) {
            state = RequestState.RECEIVING_BODY;
        } else if (state != RequestState.RECEIVING_BODY) {
            throw new IllegalStateException("Cannot receive body with state " + state);
        }
    }

    void onComplete(Headers trailers) {
        state = RequestState.COMPLETE;
        this.trailers = trailers;
    }

    void onError() {
        this.state = RequestState.ERRORED;
    }

    @Override
    public void abort() {
        throw new UserRequestAbortException();
    }

    void abort(Throwable cause) {
        if (!state.endState()) {
            this.state = RequestState.ERRORED;
        }
    }
}

class UserRequestAbortException extends RuntimeException {
    public UserRequestAbortException() {
    }

    public UserRequestAbortException(String message) {
        super(message);
    }
}