package ronin.muserver;

import io.netty.channel.ChannelHandlerContext;
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
	 *
	 * If behind a reverse proxy, this URI should be the URI that the client saw when making the request.
	 */
	URI uri();

	/**
	 * The URI of the request for this server.
	 *
	 * If behind a reverse proxy, this will be different from {@link #uri()} as it is the actual server URI rather
	 * than what the client sees.
	 */
	URI serverURI();

	Headers headers();

	/**
	 * The input stream of the request, if there was a request body.
	 *
	 * Note: this can only be read once.
	 */
	Optional<InputStream> inputStream();

	/**
	 * Returns the request body as a string.
	 *
	 * This is a blocking call which waits until the whole request is available. If you need the raw bytes, or to stream
	 * the request body, then use the {@link #inputStream()} instead.
	 *
	 * The content type of the request body is assumed to be UTF-8.
	 *
	 * Note: this can only be read once
	 *
	 * @return The content of the request body, or an empty string if there is no request body
	 * @throws IOException if there is an exception during reading the request, e.g. if the HTTP connection is stopped during a request
	 */
	String bodyAsString() throws IOException;


	/**
	 * Gets the path of the URL, e.g. for <code>https://www.example.org/some/path.html?value=a</code> it returns <code>/some/path.html</code>
     * @return The path that was requested
	 */
	String getPath();

	/**
	 * Gets the querystring value with the given name, or empty string if there is no parameter with that name.
	 * <p>
	 * If there are multiple parameters with the same name, the first one is returned.
	 */
	String getParameter(String name);

	/**
	 * Gets all the querystring parameters with the given name, or an empty list if none are found.
	 */
	List<String> getParameters(String name);
}

class NettyRequestAdapter implements MuRequest {
	private final HttpRequest request;
	private final URI serverUri;
	private final URI uri;
	private final QueryStringDecoder queryStringDecoder;
	private final HttpMethod method;
	private final Headers headers;
	private InputStream inputStream;

	public NettyRequestAdapter(ChannelHandlerContext ctx, HttpRequest request) {
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
		return inputStream == null ? Optional.empty() : Optional.of(inputStream);
	}

	@Override
	public String bodyAsString() throws IOException {
		if (inputStream != null) {
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

	@Override
	public String getParameter(String name) {
		List<String> values = queryStringDecoder.parameters().get(name);
		if (values == null) {
			return "";
		}
		return values.get(0);
	}

	@Override
	public List<String> getParameters(String name) {
		List<String> values = queryStringDecoder.parameters().get(name);
		if (values == null) {
			return Collections.emptyList();
		}
		return values;
	}

	@Override
	public String getPath() {
		return queryStringDecoder.path();
	}

	void inputStream(InputStream stream) {
		this.inputStream = stream;
	}

	@Override
	public String toString() {
		return method().name() + " " + uri();
	}
}
