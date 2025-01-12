package io.muserver;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * A handler that can establish a web socket based on web socket upgrade requests.
 * Create with {@link WebSocketHandlerBuilder#webSocketHandler()}
 */
public class WebSocketHandler implements MuHandler {

    private final MuWebSocketFactory factory;
    private final @Nullable String path;
    private final WebSocketHandlerBuilder.Settings settings;

    WebSocketHandler(MuWebSocketFactory factory, @Nullable String path, WebSocketHandlerBuilder.Settings settings) {
        this.factory = factory;
        this.path = path;
        this.settings = settings;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        if (request.method() != Method.GET) {
            return false;
        }
        if (Mutils.hasValue(path) && !path.equals(request.relativePath())) {
            return false;
        }
        if (!request.headers().contains(HeaderNames.UPGRADE, HeaderValues.WEBSOCKET, true)) {
            return false;
        }
        if (request.httpVersion() != HttpVersion.HTTP_1_1) {
            throw new HttpException(HttpStatus.HTTP_VERSION_NOT_SUPPORTED_505, "Websockets not supported for " + request.httpVersion());
        }
        if (!request.headers().connection().contains(HeaderValues.UPGRADE.toString(), true)) {
            throw HttpException.badRequest("No upgrade token in the connection header");
        }

        if (!request.headers().contains(HeaderNames.SEC_WEBSOCKET_VERSION, HeaderValues.THIRTEEN, false)) {
            HttpException nope = new HttpException(HttpStatus.UPGRADE_REQUIRED_426);
            nope.responseHeaders().set(HeaderNames.SEC_WEBSOCKET_VERSION, HeaderValues.THIRTEEN);
            throw nope;
        }

        MuWebSocket muWebSocket = factory.create(request, response.headers());
        if (muWebSocket == null) {
            return false;
        }

        response.status(HttpStatus.SWITCHING_PROTOCOLS_101);
        var responseKey = acceptKey(request.headers().get(HeaderNames.SEC_WEBSOCKET_KEY));
        response.headers().set(HeaderNames.SEC_WEBSOCKET_ACCEPT, responseKey);
        response.headers().set(HeaderNames.SEC_WEBSOCKET_VERSION, HeaderValues.THIRTEEN);
        response.headers().set(HeaderNames.UPGRADE, HeaderValues.WEBSOCKET);
        response.headers().set(HeaderNames.CONNECTION, HeaderValues.UPGRADE);

        var wsConnection = new WebsocketConnection((Mu3Http1Connection) request.connection(), muWebSocket, settings);
        ((Http1Response)response).upgrade(wsConnection);

        return true;
    }

    static String acceptKey(@Nullable String clientKey) throws NoSuchAlgorithmException {
        if (clientKey == null || clientKey.isBlank()) {
            throw HttpException.badRequest("No valid SEC_WEBSOCKET_KEY");
        }
        String concat = clientKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest digest = MessageDigest.getInstance("SHA1");
        var bytes = digest.digest(concat.getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Override
    public String toString() {
        return "WebSocketHandler{" +
            "path='" + path + '\'' +
            ", settings=" + settings +
            '}';
    }
}
