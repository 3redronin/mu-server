package ronin.muserver;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static ronin.muserver.Cookie.nettyToMu;

public interface MuRequest {

    /**
     * @return The request method, e.g. GET or POST
     */
    Method method();

    /**
     * The URI of the request at the origin.
     * <p>
     * If behind a reverse proxy, this URI should be the URI that the client saw when making the request.
     *
     * @return The full request URI
     */
    URI uri();

    /**
     * The URI of the request for this server.
     * <p>
     * If behind a reverse proxy, this will be different from {@link #uri()} as it is the actual server URI rather
     * than what the client sees.
     *
     * @return The full request URI
     */
    URI serverURI();

    /**
     * @return The request headers
     */
    Headers headers();

    /**
     * The input stream of the request, if there was a request body.
     * <p>
     * Note: this can only be read once and cannot be used with {@link #readBodyAsString()}, {@link #formValue(String)} or {@link #formValues(String)}.
     *
     * @return {@link Optional#empty()} if there is no request body; otherwise the input stream of the request body.
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
     *
     * @param name The querystring parameter name to get
     * @return The querystring value, or an empty string
     */
    String parameter(String name);

    /**
     * Gets all the querystring parameters with the given name, or an empty list if none are found.
     *
     * @param name The querystring parameter name to get
     * @return All values of the parameter with the given name
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
     * @return The value of the form element with the given name, or an empty string
     * @throws IOException Thrown when there is an error while reading the form, e.g. if a user closes their
     *                     browser before the form is fully read into memory.
     */
    String formValue(String name) throws IOException;

    /**
     * Gets the form values with the given name, or empty list if there is no form value with that name.
     * <p>
     * Note: this cannot be called after a call to {@link #inputStream()} or {@link #readBodyAsString()}
     *
     * @param name The name of the form element to get
     * @return All values of the form element with the given name
     * @throws IOException Thrown when there is an error while reading the form, e.g. if a user closes their
     *                     browser before the form is fully read into memory.
     */
    List<String> formValues(String name) throws IOException;

    /**
     * Gets all the client-sent cookies
     * @return A set of cookie objects
     */
    Set<Cookie> cookies();

    /**
     * Gets the client-sent cookie with the given name
     * @param name The name of the cookie
     * @return The cookie, or {@link Optional#empty()} if there is no cookie with that name.
     */
    Optional<Cookie> cookie(String name);
}

class NettyRequestAdapter implements MuRequest {
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

    public NettyRequestAdapter(String proto, HttpRequest request) {
        this.request = request;
        this.serverUri = URI.create(proto + "://" + request.headers().get(HeaderNames.HOST) + request.uri());
        this.uri = getUri(request, serverUri);
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
        String portString = (port != 80 && port != 443 && port > 0) ? ":" + port : "";
        return URI.create(proto + "://" + host + portString + request.uri());
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
            return Collections.emptyList();
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
            this.cookies = nettyToMu(ServerCookieDecoder.STRICT.decode(headers().get(HeaderNames.COOKIE)));
        }
        return this.cookies;
    }

    @Override
    public Optional<Cookie> cookie(String name) {
        Set<Cookie> cookies = cookies();
        for (Cookie cookie : cookies) {
            if (cookie.name().equals(name)) {
                return Optional.of(cookie);
            }
        }
        return Optional.empty();
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


    public String toString() {
        return method().name() + " " + uri();
    }
}
