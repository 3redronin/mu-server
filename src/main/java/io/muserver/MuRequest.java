package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

import static io.muserver.Cookie.nettyToMu;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

/**
 * <p>An HTTP request from a client.</p>
 * <p>Call {@link #method()} and {@link #uri()} to find the method and URL of this request.</p>
 * <p>To read query string parameters, see {@link #parameter(String)}.</p>
 * <p>Request headers can be accessed by the {@link #headers()} method.</p>
 * <p>You can read the body as an input stream with {@link #inputStream()} or if you expect a string call {@link #readBodyAsString()}.
 * If form values were posted as the request, see {@link #formValue(String)}</p>
 * <p>Getting the value of a cookie by name is done by calling {@link #cookie(String)}</p>
 * <p>If you need to share request-specific data between handlers, use {@link #state(Object)} to set and {@link #state()} to get.</p>
 */
public interface MuRequest {

    /**
     * <p>Gets the content type of the request body, for example <code>application/json</code> or <code>null</code>
     * if there is no body.</p>
     * <p>Note: If the Content-Type header included a charset, then this will NOT be returned by this method.</p>
     * @return The content type of the request body (specified by the <code>Content-Type</code> request header),
     * or <code>null</code> if there is no body.
     */
    String contentType();

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
     * Gets all the uploaded files with the given name, or an empty list if none are found.
     *
     * @param name The file input name to get
     * @return All the files with the given name
     * @throws IOException Thrown when there is an error while reading the file, e.g. if a user closes their
     *                     browser before the upload is complete.
     */
    List<UploadedFile> uploadedFiles(String name) throws IOException;

    /**
     * <p>Gets the uploaded file with the given name, or null if there is no upload with that name.</p>
     * <p>If there are multiple files with the same name, the first one is returned.</p>
     *
     * @param name The querystring parameter name to get
     * @return The querystring value, or an empty string
     * @throws IOException Thrown when there is an error while reading the file, e.g. if a user closes their
     *                     browser before the upload is complete.
     */
    UploadedFile uploadedFile(String name) throws IOException;

    /**
     * <p>Gets the querystring value with the given name, or empty string if there is no parameter with that name.</p>
     * <p>If there are multiple parameters with the same name, the first one is returned.</p>
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
     *
     * @return A set of cookie objects
     */
    Set<Cookie> cookies();

    /**
     * Gets the value of the client-sent cookie with the given name
     *
     * @param name The name of the cookie
     * @return The cookie, or {@link Optional#empty()} if there is no cookie with that name.
     */
    Optional<String> cookie(String name);

    /**
     * <p>If this handler was added to a {@link ContextHandlerBuilder#context(String)} then this
     * will return the value of the context pre-pended with a '<code>/</code>'.</p>
     * <p>For example,
     * if this handler was added to <code>ContextHandlerBuilder.context(&quot;some context&quot;)</code>
     * this this method will return <code>/some%20context</code></p>
     *
     * @return The context of the current handler or '<code>/</code>' if there is no context.
     * @see #relativePath()
     */
    String contextPath();

    /**
     * <p>The path of this request (without query string) relative to the context. If this handler is not
     * nested within a context then it simply returns the full path of the request, otherwise it is
     * the path relative to {@link #contextPath()}.</p>
     * <p>For example, if there is a context <code>some context</code>
     * and there is a request to <code>/some%20context/some%20path</code> then this method will return
     * <code>/some%20path</code></p>
     *
     * @return The relative path of this request.
     * @see #contextPath()
     */
    String relativePath();

    /**
     * <p>Gets request-specific state that was added with {@link #state(Object)}.</p>
     * <p>An example is getting user information in a view handler that was previously set by an authentication handler.</p>
     * @return An object previously set by {@link #state(Object)}, or <code>null</code> if it was never set.
     */
    Object state();

    /**
     * <p>This associates an arbitrary object with the current request. Use this to pass request-specific data between
     * handlers.</p>
     * <p>An example use case is if you have a authentication handler that uses this method to set user information on
     * the request, and then a subsequent handler calls {@link #state()} to get the user info.</p>
     * @param value Any object to store as state.
     */
    void state(Object value);

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
                        System.out.println("Unrecognised body part: " + bodyHttpData.getClass());
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
        if (multipartRequestDecoder != null) {
            multipartRequestDecoder.destroy();
        }
    }
}
