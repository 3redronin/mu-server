package ronin.muserver;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;

public interface MuRequest {

    HttpMethod method();

    /**
     * The URI of the request at the origin.
     * <p>
     * If behind a reverse proxy, this URI should be the URI that the client saw when making the request.
     */
    URI uri();

    /**
     * The URI of the request for this server.
     * <p>
     * If behind a reverse proxy, this will be different from {@link #uri()} as it is the actual server URI rather
     * than what the client sees.
     */
    URI serverURI();

    Headers headers();

    /**
     * The input stream of the request, if there was a request body.
     * <p>
     * Note: this can only be read once and cannot be used with {@link #readBodyAsString()}, {@link #formValue(String)} or {@link #formValues(String)}.
     */
    Optional<InputStream> inputStream();

    /**
     * Returns the request body as a string.
     * <p>
     * This is a blocking call which waits until the whole request is available. If you need the raw bytes, or to stream
     * the request body, then use the {@link #inputStream()} instead.
     * <p>
     * The content type of the request body is assumed to be UTF-8.
     * <p>
     * Note: this can only be read once and cannot be used with {@link #inputStream()} ()}, {@link #formValue(String)} or {@link #formValues(String)}.
     *
     * @return The content of the request body, or an empty string if there is no request body
     * @throws IOException if there is an exception during reading the request, e.g. if the HTTP connection is stopped during a request
     */
    String readBodyAsString() throws IOException;

    /**
     * Gets the querystring value with the given name, or empty string if there is no parameter with that name.
     * <p>
     * If there are multiple parameters with the same name, the first one is returned.
     */
    String parameter(String name);

    /**
     * Gets all the querystring parameters with the given name, or an empty list if none are found.
     */
    List<String> parameters(String name);

    /**
     * Gets the form value with the given name, or empty string if there is no form value with that name.
     * <p>
     * If there are multiple form elements with the same name, the first one is returned.
     * <p>
     * Note: this cannot be called after a call to {@link #inputStream()} or {@link #readBodyAsString()}
     *
     * @param name The name of the form element to get
     */
    String formValue(String name) throws IOException;

    /**
     * Gets the form values with the given name, or empty list if there is no form value with that name.
     * <p>
     * Note: this cannot be called after a call to {@link #inputStream()} or {@link #readBodyAsString()}
     *
     * @param name The name of the form element to get
     */
    List<String> formValues(String name) throws IOException;
}

class NettyRequestAdapter implements MuRequest {
    private final HttpRequest request;
    private final URI serverUri;
    private final URI uri;
    private final QueryStringDecoder queryStringDecoder;
    private final HttpMethod method;
    private final Headers headers;
    private InputStream inputStream;
    private QueryStringDecoder formDecoder;
    private boolean bodyRead = false;

    public NettyRequestAdapter(HttpRequest request) {
        this.request = request;
        String proto = "http"; // TODO: figure this out based on the request or current channel
        this.serverUri = URI.create(proto + "://" + request.headers().get("Host") + request.uri());
        this.uri = getUri(request, serverUri);
        this.queryStringDecoder = new QueryStringDecoder(request.uri(), true);
        this.method = HttpMethod.fromNetty(request.method());
        this.headers = new Headers(request.headers());
    }

    private static URI getUri(HttpRequest request, URI serverUri) {
        HttpHeaders h = request.headers();
        String proto = h.get("X-Forwarded-Proto", serverUri.getScheme());
        String host = h.get("X-Forwarded-Host", serverUri.getHost());
        int port = h.getInt("X-Forwarded-Port", serverUri.getPort());
        String portString = (port != 80 && port != 443) ? ":" + port : "";
        return URI.create(proto + "://" + host + portString + request.uri());
    }

    @Override
    public HttpMethod method() {
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
        if (inputStream == null) {
            return Optional.empty();
        } else {
            claimingBodyRead();
            return Optional.of(inputStream);
        }
    }

    @Override
    public String readBodyAsString() throws IOException {
        if (inputStream != null) {
            claimingBodyRead();
            StringBuilder sb = new StringBuilder();
            try (InputStream in = inputStream) {
                byte[] buffer = new byte[2048]; // TODO: what should this be?
                int read;
                while ((read = in.read(buffer)) > -1) {
                    sb.append(new String(buffer, 0, read, UTF_8));
                }
            }
            return sb.toString();
        } else {
            return "";
        }
    }

    private void claimingBodyRead() {
        if (bodyRead) {
            throw new IllegalStateException("The body of the request message cannot be read twice. This can happen when calling any 2 of inputStream(), readBodyAsString(), or getFormValue() methods.");
        }
        bodyRead = true;
    }

    @Override
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

    @Override
    public List<String> parameters(String name) {
        return getMultipleParams(name, queryStringDecoder);
    }

    private static List<String> getMultipleParams(String name, QueryStringDecoder queryStringDecoder) {
        List<String> values = queryStringDecoder.parameters().get(name);
        if (values == null) {
            return Collections.emptyList();
        }
        return values;
    }

    @Override
    public String formValue(String name) throws IOException {
        ensureFormDataLoaded();
        return getSingleParam(name, formDecoder);
    }
    @Override
    public List<String> formValues(String name) throws IOException {
        ensureFormDataLoaded();
        return getMultipleParams(name, formDecoder);
    }

    private void ensureFormDataLoaded() throws IOException {
        if (formDecoder == null) {
            String body = readBodyAsString();
            formDecoder = new QueryStringDecoder(body, false);
        }
    }

    void inputStream(InputStream stream) {
        this.inputStream = stream;
    }

    @Override
    public String toString() {
        return method().name() + " " + uri();
    }
}
