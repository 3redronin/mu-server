package io.muserver;

import jakarta.ws.rs.core.MediaType;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static java.lang.Math.min;

class Mu3Request implements MuRequest {
    private final HttpConnection connection;
    private final Method method;
    private final URI requestUri;
    private final URI serverUri;
    private final HttpVersion httpVersion;
    private final FieldBlock mu3Headers;
    private final BodySize bodySize;
    private final InputStream body;
    @Nullable private MuForm form;
    @Nullable
    private BaseResponse response;
    private final long startTime;
    @Nullable private QueryString query;
    @Nullable private Map<String, Object> attributes;
    private String contextPath = "";
    private String relativePath;
    @Nullable private List<Cookie> cookies;
    private boolean bodyClaimed;
    @Nullable private Mu3AsyncHandleImpl asyncHandle;

    Mu3Request(HttpConnection connection,
               Method method,
               URI requestUri,
               URI serverUri,
               HttpVersion httpVersion,
               FieldBlock mu3Headers,
               BodySize bodySize,
               InputStream body) {
        this.connection = connection;
        this.method = method;
        this.requestUri = requestUri;
        this.serverUri = serverUri;
        this.httpVersion = httpVersion;
        this.mu3Headers = mu3Headers;
        this.bodySize = bodySize;
        this.body = body;
        this.startTime = System.currentTimeMillis();
        this.relativePath = requestUri.getRawPath();
    }

    @Nullable
    @Override
    public String contentType() {
        return mu3Headers.get("content-type");
    }

    private void claimbody() {
        if (bodyClaimed) {
            throw new IllegalStateException("The body of the request message cannot be read twice. This can happen when calling any 2 of inputStream(), readBodyAsString(), or form() methods.");
        }
        bodyClaimed = true;
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
        return requestUri;
    }

    @Override
    public URI serverURI() {
        return serverUri;
    }

    @Override
    public Headers headers() {
        return mu3Headers;
    }

    @Override
    public Optional<InputStream> inputStream() {
        return bodySize == BodySize.NONE ? Optional.empty() : Optional.of(body());
    }

    @Override
    public InputStream body() {
        return body;
    }

    @Override
    public BodySize declaredBodySize() {
        return bodySize;
    }

    @Override
    public String readBodyAsString() throws IOException {
        claimbody();
        Long size = bodySize.size();
        if (size == null || size > 4096) {
            return streamBodyToString(Headtils.bodyCharset(mu3Headers, true));
        } else if (size == 0) {
            return "";
        } else {
            try (InputStream b = body()) {
                return new String(b.readAllBytes(),
                    Headtils.bodyCharset(mu3Headers, true));
            }
        }
    }

    @NotNull
    private String streamBodyToString(Charset cs) throws IOException {
        try (var reader = new InputStreamReader(body(), cs)) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[4096];
            int charsRead;
            while ((charsRead = reader.read(buffer)) != -1) {
                result.append(buffer, 0, charsRead);
            }
            return result.toString();
        }
    }

    @Override
    public RequestParameters query() {
        if (this.query == null) {
            this.query = QueryString.parse(serverUri.getRawQuery());
        }
        return this.query;
    }

    @Override
    public MuForm form() throws IOException {
        if (this.form == null) {
            claimbody();
            MediaType bodyType = mu3Headers.contentType();
            if (bodyType == null) {
                this.form = EmptyForm.VALUE;
            } else {
                String type = bodyType.getType().toLowerCase();
                String subtype = bodyType.getSubtype().toLowerCase();
                if ("application".equals(type) && "x-www-form-urlencoded".equals(subtype)) {
                    String text = streamBodyToString(StandardCharsets.UTF_8);
                    this.form = UrlEncodedMuForm.parse(text);
                } else if ("multipart".equals(type) && "form-data".equals(subtype)) {
                    var charset = Headtils.bodyCharset(mu3Headers, true);
                    String boundary = bodyType.getParameters().get("boundary");
                    if (Mutils.nullOrEmpty(boundary)) {
                        throw HttpException.badRequest("No boundary specified in the multipart form-data");
                    }
                    Long declaredSize = bodySize.size();
                    int bufferSize = (int) min(8192, declaredSize != null ? declaredSize : 8192);
                    try (InputStream b = body()) {
                        MultipartFormParser formParser = new MultipartFormParser(server().tempDir(),
                            boundary, b, bufferSize, charset);
                        this.form = formParser.parseFully();
                    }
                } else {
                    throw HttpException.badRequest("Unrecognised form type " + bodyType);
                }
            }
        }
        return this.form;
    }

    @Override
    public List<Cookie> cookies() {
        if (this.cookies == null) {
            cookies = mu3Headers.cookies();
        }
        return this.cookies;
    }

    @Override
    public Optional<String> cookie(String name) {
        return cookies().stream()
            .filter(c -> c.name().equals(name))
            .map(Cookie::value)
            .filter(Objects::nonNull)
            .findFirst();
    }

    @Override
    public String contextPath() {
        return contextPath;
    }

    @Override
    public String relativePath() {
        return relativePath;
    }

    @Nullable
    @Override
    public Object attribute(String key) {
        return attributes != null ? attributes.get(key) : null;
    }

    @Override
    public void attribute(String key, @Nullable Object value) {
        if (value == null) {
            attributes().remove(key);
        } else {
            attributes().put(key, value);
        }
    }

    @Override
    public Map<String, Object> attributes() {
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        return attributes;
    }

    @Deprecated
    @Override
    public AsyncHandle handleAsync() {
        if (asyncHandle == null) {
            assert response != null;
            asyncHandle = new Mu3AsyncHandleImpl(this, response);
        }
        return asyncHandle;
    }

    @Override
    public boolean isAsync() {
        return asyncHandle != null;
    }

    @Override
    public HttpVersion httpVersion() {
        return httpVersion;
    }

    @Override
    public HttpConnection connection() {
        return connection;
    }

    public void addContext(String contextToAdd) {
        String ctx = normaliseContext(contextToAdd);
        this.contextPath += ctx;
        this.relativePath = relativePath.substring(ctx.length());
    }

    public void setPaths(String contextPath, String relativePath) {
        this.contextPath = contextPath;
        this.relativePath = relativePath;
    }

    private String normaliseContext(String contextToAdd) {
        String normal = contextToAdd;
        if (normal.endsWith("/")) {
            normal = normal.substring(0, normal.length() - 1);
        }
        if (!normal.startsWith("/")) {
            normal = "/" + normal;
        }
        return normal;
    }

    @Override
    public String toString() {
        return httpVersion.version() + " " + method + " " + serverUri;
    }

    public boolean cleanup() {
        try {
            if (body instanceof Http1BodyStream) {
                var http1Body = (Http1BodyStream) body;
                assert response != null;
                boolean throwIfTooBig = response.status() != HttpStatus.CONTENT_TOO_LARGE_413;
                var bodyState = http1Body.discardRemaining(throwIfTooBig);
                return bodyState == Http1BodyStream.State.EOF && !http1Body.tooBig();
            } else {
                return true;
            }
        } finally {
            if (form instanceof MultipartForm) {
                ((MultipartForm) form).cleanup();
            }
        }
    }

    public boolean completedSuccessfully() {
        if (body instanceof Http1BodyStream) {
            return ((Http1BodyStream) body).state() == Http1BodyStream.State.EOF;
        }
        return true;
    }

    @Nullable
    public Mu3AsyncHandleImpl getAsyncHandle() {
        return asyncHandle;
    }

    public void setResponse(BaseResponse response) {
        this.response = response;
    }
}