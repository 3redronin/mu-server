package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.*;

import static io.muserver.MessageBodyBit.EOFMsg;
import static io.muserver.MessageBodyBit.EndOfBodyBit;
import static io.muserver.ParseUtils.*;

class Http1MessageParser implements Http1MessageReader {
    private final Queue<HttpRequestTemp> requestQueue;
    private final InputStream source;
    private final int maxHeadersLength;
    private final int maxUrlLength;
    private final int maxBufferSize;
    private HttpMessageTemp exchange;

    Http1MessageParser(HttpMessageType type, Queue<HttpRequestTemp> requestQueue, InputStream source, int maxHeadersLength, int maxUrlLength) {
        this.requestQueue = requestQueue;
        this.source = source;
        this.maxHeadersLength = maxHeadersLength;
        this.maxUrlLength = maxUrlLength;
        this.maxBufferSize = Math.max(maxHeadersLength, maxUrlLength);
        if (type == HttpMessageType.REQUEST) {
            exchange = HttpRequestTemp.empty();
            state = ParseState.REQUEST_START;
        } else {
            exchange = HttpResponseTemp.empty();
            state = ParseState.RESPONSE_START;
        }

    }

    /**
     * The number of bytes remaining to be sent in a fixed body size, or in the current chunk of chunked data (or MAX_LENGTH for unspecified lengths)
     */
    private long remainingBytesToProxy = 0L;
    private ParseState state;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    @Nullable
    private String headerName = null;
    @Nullable
    private FieldBlock trailers = null;
    @Nullable
    private FieldBlock pendingTrailers = null;
    private boolean failed;
    private long curHeadersLen = 0L;

    final byte[] readBuffer =  new byte[8192];
    private int position = 0;
    private int limit = 0;
    private int remaining() {
        return limit - position;
    }
    private boolean hasRemaining() {
        return remaining() > 0;
    }

    @Override
    public Http1ConnectionMsg readNext() throws IOException, ParseException {
        if (failed) throw new ParseException("Cannot resume a malformed HTTP message", position);
        try {
            return readNextMessage();
        } catch (IOException | ParseException | HttpException | IllegalArgumentException e) {
            failed = true;
            pendingTrailers = null;
            trailers = null;
            throw e;
        }
    }

    private Http1ConnectionMsg readNextMessage() throws IOException, ParseException {
        if (limit == -1) return EOFMsg;
        while (true) {
            if (!hasRemaining()) {
                position = 0;
                limit = source.read(readBuffer);
                if (limit == -1) {
                    if (state == ParseState.UNSPECIFIED_BODY) {
                        return EndOfBodyBit;
                    }
                    if ((state != ParseState.REQUEST_START && state != ParseState.RESPONSE_START)
                        || buffer.size() != 0) {
                        throw new ParseException("Incomplete HTTP message at " + state, position);
                    }
                    return EOFMsg;
                }
            }
            while (hasRemaining()) {
                byte b = readBuffer[position];
                switch (state) {
                    case REQUEST_START: {
                        trailers = null;
                        if (ParseUtils.isTChar(b)) {
                            requestQueue.offer((HttpRequestTemp) exchange);
                            state = ParseState.METHOD;
                            append(buffer, b);
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case METHOD: {
                        if (ParseUtils.isTChar(b)) {
                            append(buffer, b);
                        } else if (b == SP) {
                            try {
                                request().setMethod(Method.valueOf(consumeAscii(buffer)));
                            } catch (IllegalArgumentException e) {
                                request().setRejectRequest(new HttpException(HttpStatus.METHOD_NOT_ALLOWED_405));
                                request().setMethod(Method.GET); // bit weird - but we need some method
                            }
                            state = ParseState.REQUEST_TARGET;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case REQUEST_TARGET: {
                        var req = request();
                        if (isVChar(b)) { // todo: only allow valid target chars
                            var reject = req.getRejectRequest();
                            var bad = reject != null && reject.status().sameCode(HttpStatus.URI_TOO_LONG_414);
                            if (!bad && buffer.size() < maxUrlLength) {
                                append(buffer, b);
                            } else if (!bad) {
                                buffer.reset();
                                append(buffer, (byte) '/'); // the request won't last long - give it a temp URL
                                request().setRejectRequest(new HttpException(HttpStatus.URI_TOO_LONG_414));
                            }
                        } else if (b == SP) {
                            req.setUrl(consumeAscii(buffer));
                            state = ParseState.HTTP_VERSION;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case HTTP_VERSION: {
                        if (b == CR) {
                            state = ParseState.REQUEST_LINE_ENDING;
                        } else {
                            if (isVChar(b)) {
                                append(buffer, b);
                            } else throw new ParseException("state=" + state + " b=" + b, position);
                        }
                        break;
                    }

                    case REQUEST_LINE_ENDING: {
                        if (b == LF) {
                            exchange.setHttpVersion(consumeHttpVersion(buffer));
                            curHeadersLen = 0;
                            state = ParseState.HEADER_START;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case RESPONSE_START: {
                        trailers = null;
                        if (b == SP) {
                            exchange.setHttpVersion(consumeHttpVersion(buffer));
                            state = ParseState.STATUS_CODE;
                        } else {
                            if (isVChar(b)) {
                                append(buffer, b);
                            } else throw new ParseException("state=" + state + " b=" + b, position);
                        }
                        break;
                    }

                    case STATUS_CODE: {
                        if (isDigit(b)) {
                            append(buffer, b);
                            if (buffer.size() > 3) throw new ParseException("status code too long", position);
                        } else if (b == SP) {
                            var code = Integer.parseInt(consumeAscii(buffer));
                            response().setStatusCode(code);
                            if (code >= 200 || code == 101) {
                                HttpRequestTemp correspondingReq = requestQueue.poll();
                                if (correspondingReq == null) {
                                    throw new ParseException("Got a response without a request", position);
                                }
                                response().setRequest(correspondingReq);
                            }
                            state = ParseState.REASON_PHRASE;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case REASON_PHRASE: {
                        if (isVChar(b) || isOWS(b)) {
                            append(buffer, b);
                        } else if (b == CR) {
                            state = ParseState.STATUS_LINE_ENDING;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case STATUS_LINE_ENDING: {
                        if (b == LF) {
                            response().setReason(consumeAscii(buffer));
                            curHeadersLen = 0;
                            state = ParseState.HEADER_START;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case HEADER_START: {
                        onHeaderChar();
                        if (isTChar(b)) {
                            append(buffer, toLower(b));
                            state = ParseState.HEADER_NAME;
                        } else if (b == CR) {
                            state = ParseState.HEADERS_ENDING;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case HEADER_NAME: {
                        var isOkay = onHeaderChar();
                        if (isTChar(b)) {
                            if (isOkay) {
                                append(buffer, toLower(b));
                            }
                        } else if (b == COLON) {
                            headerName = consumeAscii(buffer);
                            if (headerName.isEmpty() && isOkay) throw new ParseException("Empty header name", position);
                            state = ParseState.HEADER_NAME_ENDED;
                        } else throw new ParseException("Invalid header name " + b, position);
                        break;
                    }

                    case HEADER_NAME_ENDED: {
                        var isOkay = onHeaderChar();
                        if (isOWS(b)) {
                            // skip it
                        } else if (isFieldContent(b)) {
                            if (isOkay) {
                                append(buffer, b);
                            }
                            state = ParseState.HEADER_VALUE;
                        } else if (b == CR) {
                            state = ParseState.HEADER_VALUE_ENDING;
                        } else {
                            throw new ParseException("Invalid header value " + b, position);
                        }
                        break;
                    }

                    case HEADER_VALUE: {
                        var isOkay = onHeaderChar();
                        if (isFieldContent(b) || isOWS(b)) {
                            if (isOkay) {
                                append(buffer, b);
                            }
                        } else if (b == CR) {
                            state = ParseState.HEADER_VALUE_ENDING;
                        } else throw new ParseException("Invalid header value " + b, position);
                        break;
                    }

                    case HEADER_VALUE_ENDING: {
                        onHeaderChar();
                        if (b == LF) {
                            state = ParseState.HEADER_LINE_ENDED;
                        } else throw new ParseException("No LF after CR at " + state, position);
                        break;
                    }

                    case HEADER_LINE_ENDED: {
                        if (isOWS(b)) {
                            if (exchange instanceof HttpRequestTemp) {
                                throw new ParseException("Obsolete request field folding", position);
                            }
                            if (onHeaderChar()) append(buffer, SP);
                            state = ParseState.HEADER_NAME_ENDED;
                        } else {
                            if (curHeadersLen <= maxHeadersLength) {
                                FieldBlock fields = pendingTrailers == null ? exchange.headers() : pendingTrailers;
                                fields.add(Objects.requireNonNull(headerName), buffer.toString(StandardCharsets.ISO_8859_1));
                            }
                            buffer.reset();
                            headerName = null;
                            state = ParseState.HEADER_START;
                            continue;
                        }
                        break;
                    }

                    case HEADERS_ENDING: {
                        if (b == LF) {
                            if (pendingTrailers != null) {
                                RequestTrailers.validate(pendingTrailers);
                                trailers = pendingTrailers;
                                pendingTrailers = null;
                                position++;
                                onMessageEnded();
                                return EndOfBodyBit;
                            }
                            var exc = exchange;
                            BodySize body;
                            try {
                                for (FieldLine field : exc.headers().lineIterator()) {
                                    if (field.value().length() == 0
                                        && (HeaderNames.CONTENT_LENGTH.equals(field.name())
                                        || HeaderNames.TRANSFER_ENCODING.equals(field.name()))) {
                                        throw new IllegalStateException("Empty HTTP framing field");
                                    }
                                }
                                body = exc.bodyTransferSize();
                            } catch (IllegalStateException invalidFraming) {
                                String framingMessage = Objects.requireNonNullElse(
                                    invalidFraming.getMessage(), "Invalid HTTP message framing");
                                if (!(exc instanceof HttpRequestTemp)) {
                                    throw new ParseException(framingMessage, position);
                                }
                                // The body boundary is unknown, so reject the request and
                                // close instead of trying to parse another message from it.
                                var request = (HttpRequestTemp) exc;
                                var rejection = HttpException.badRequest(framingMessage);
                                rejection.responseHeaders().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
                                request.setRejectRequest(rejection);
                                body = BodySize.NONE;
                            }
                            exc.setBodySize(body);
                            switch (body.type()) {
                                case FIXED_SIZE: {
                                    long len = Objects.requireNonNull(body.size());
                                    state = ParseState.FIXED_SIZE_BODY;
                                    remainingBytesToProxy = len;
                                    break;
                                }
                                case CHUNKED: {
                                    state = ParseState.CHUNK_START;
                                    break;
                                }
                                case UNSPECIFIED: {
                                    state = ParseState.UNSPECIFIED_BODY;
                                    remainingBytesToProxy = Long.MAX_VALUE;
                                    break;
                                }
                                case NONE: {
                                    onMessageEnded();
                                    break;
                                }
                            }

                            position++;
                            return exc;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                    }

                    case FIXED_SIZE_BODY:
                        case UNSPECIFIED_BODY: {
                        return sendContent();
                    }

                    case CHUNK_START: {
                        if (isHexDigit(b)) {
                            state = ParseState.CHUNK_SIZE;
                            append(buffer, b);
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case CHUNK_SIZE: {
                        if (isHexDigit(b)) {
                            append(buffer, b);
                        } else {
                            if (b == SEMICOLON) {
                                state = ParseState.CHUNK_EXTENSIONS;
                            } else if (b == CR) {
                                state = ParseState.CHUNK_HEADER_ENDING;
                            } else throw new ParseException("state=" + state + " b=" + b, position);
                            try {
                                remainingBytesToProxy = Long.parseLong(consumeAscii(buffer), 16);
                            } catch (NumberFormatException invalidChunkSize) {
                                throw new ParseException("Invalid chunk size", position);
                            }
                        }
                        break;
                    }

                    case CHUNK_EXTENSIONS: {
                        if (isVChar(b) || isOWS(b)) {
                            append(buffer, b);
                        } else if (b == CR) {
                            validateChunkExtensions(consumeAscii(buffer), position);
                            state = ParseState.CHUNK_HEADER_ENDING;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case CHUNK_HEADER_ENDING: {
                        if (b == LF) {
                            // remainingBytesToProxy has the chunk size in it
                            if (remainingBytesToProxy == 0L) {
                                pendingTrailers = new FieldBlock();
                                curHeadersLen = 0;
                                state = ParseState.HEADER_START;
                            } else {
                                state = ParseState.CHUNK_DATA;
                            }
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case CHUNK_DATA: {
                        return sendContent();
                    }

                    case CHUNK_DATA_READ: {
                        if (b == CR) {
                            state = ParseState.CHUNK_DATA_ENDING;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case CHUNK_DATA_ENDING: {
                        if (b == LF) {
                            state = ParseState.CHUNK_START;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case WEBSOCKET: {
                        throw new UnsupportedOperationException("No websockets yet");
                    }
                }
                position++;
            }
        }
    }

    private boolean onHeaderChar() throws ParseException {
        curHeadersLen++;
        if (curHeadersLen <= maxHeadersLength) return true;
        if (pendingTrailers != null || exchange instanceof HttpResponseTemp) {
            throw new ParseException("HTTP field section is too large", position);
        }
        var req = exchange;
        if (req instanceof HttpRequestTemp && ((HttpRequestTemp) req).getRejectRequest() == null) {
            ((HttpRequestTemp) req).setRejectRequest(new HttpException(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE_431));
        }
        return false;
    }

    private HttpRequestTemp request() { return (HttpRequestTemp) exchange; }
    private HttpResponseTemp response() { return (HttpResponseTemp) exchange; }

    private MessageBodyBit sendContent() {
        int remainingInBuffer = remaining();
        int numberToTransfer = (int)Math.min(remainingBytesToProxy, remainingInBuffer);
        remainingBytesToProxy -= numberToTransfer;
        int start = position;
        position += numberToTransfer;

        boolean isLast;
        if (state == ParseState.CHUNK_DATA) {
            if (remainingBytesToProxy == 0L) {
                state = ParseState.CHUNK_DATA_READ;
            }
            isLast = false;
        } else {
            isLast = remainingBytesToProxy == 0L;
        }
        if (isLast) {
            onMessageEnded();
        }
        return new MessageBodyBit(readBuffer, start, numberToTransfer, isLast);
    }

    private void onMessageEnded() {
        var exc = exchange;
        if (exc instanceof HttpRequestTemp) {
            if (((HttpRequestTemp)exc).isWebsocketUpgrade()) {
                this.state = ParseState.WEBSOCKET;
            } else {
                this.exchange = HttpRequestTemp.empty();
                this.state = ParseState.REQUEST_START;
            }
        } else {
            if (exc.headers().containsValue(HeaderNames.UPGRADE, HeaderValues.WEBSOCKET, false)) {
                this.state = ParseState.WEBSOCKET;
            } else {
                this.exchange = HttpResponseTemp.empty();
                this.state = ParseState.RESPONSE_START;
            }
        }
    }

    @Nullable
    FieldBlock takeTrailers() {
        var current = trailers;
        trailers = null;
        return current;
    }

    HttpRequestTemp rejectInvalidRequest(ParseException failure) {
        HttpRequestTemp request = request();
        if (request.getMethod() == null) request.setMethod(Method.GET);
        if (request.getHttpVersion() == null) request.setHttpVersion(HttpVersion.HTTP_1_1);
        if (request.getUrl().isEmpty()) request.setUrl("/");
        request.setBodySize(BodySize.NONE);
        HttpException rejection = HttpException.badRequest(
            Objects.requireNonNullElse(failure.getMessage(), "Malformed HTTP request"));
        rejection.responseHeaders().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
        request.setRejectRequest(rejection);
        return request;
    }

    private enum ParseState {
        REQUEST_START,
        RESPONSE_START,
        METHOD,
        REQUEST_TARGET,
        HTTP_VERSION,
        REQUEST_LINE_ENDING,
        STATUS_CODE,
        REASON_PHRASE,
        STATUS_LINE_ENDING,
        HEADER_START,
        HEADER_NAME,
        HEADER_NAME_ENDED,
        HEADER_VALUE,
        HEADER_VALUE_ENDING,
        HEADER_LINE_ENDED,
        HEADERS_ENDING,
        FIXED_SIZE_BODY,
        UNSPECIFIED_BODY,
        CHUNK_START,
        CHUNK_SIZE,
        CHUNK_EXTENSIONS,
        CHUNK_HEADER_ENDING,
        CHUNK_DATA,
        WEBSOCKET,
        CHUNK_DATA_READ,
        CHUNK_DATA_ENDING,
    }


    @Override
    public String toString() {
        return getClass().getSimpleName() + " "  + this.state;
    }

    private void append(ByteArrayOutputStream baos, byte b) throws ParseException {
        baos.write(b);
        if (baos.size() > maxBufferSize) throw new ParseException("Buffer is " + baos.size() + " bytes", position);
    }

    private static boolean isVChar(byte b) { return b >= (byte)0x21 && b <= (byte)0x7E; }
    private static boolean isFieldContent(byte b) { return isVChar(b) || b < 0; }

    static boolean isTChar(byte b) {
        // tchar = '!' / '#' / '$' / '%' / '&' / ''' / '*' / '+' / '-' / '.' /
        //    '^' / '_' / '`' / '|' / '~' / DIGIT / ALPHA
        return (b == (byte)33)
            || (b >= (byte)35 && b <= (byte)39)
                || (b == (byte)42) || (b == (byte)43) || (b == (byte)45) || (b == (byte)46)
            || (b >= ZERO && b <= NINE) // 0-9
                || (b >= A && b <= Z) // A-Z
                || (b >= (byte)94 && b <= (byte)122) // ^, ), `, a-z
                || (b == (byte)124) || (b == (byte)126);
    }

    private static boolean isUpperCase(byte b) { return b >= A && b <= Z; }
    private static boolean isCR(byte b){ return b == CR; }
    private static boolean isLF(byte b){ return b == LF; }
    private static boolean isOWS(byte b){ return b == SP || b == HTAB; }
    private static byte toLower(byte b) {
        if (b < A || b > Z) return b;
        return (byte)(b + 32);
    }
    private static boolean isDigit(byte b) { return b >= ZERO && b <= NINE; }
    private static boolean isHexDigit(byte b) { return (b >= A && b <= F) || (b >= ZERO && b <= NINE) || (b >= A_LOWER && b <= F_LOWER); }

    private static void validateChunkExtensions(String extensions, int position) throws ParseException {
        int i = 0;
        while (true) {
            i = skipOWS(extensions, i);
            i = readToken(extensions, i, position, "chunk extension name");
            i = skipOWS(extensions, i);
            if (i < extensions.length() && extensions.charAt(i) == '=') {
                i = skipOWS(extensions, i + 1);
                if (i >= extensions.length()) throw new ParseException("Missing chunk extension value", position);
                if (extensions.charAt(i) == '"') {
                    i = readQuotedString(extensions, i + 1, position);
                } else {
                    i = readToken(extensions, i, position, "chunk extension value");
                }
                i = skipOWS(extensions, i);
            }
            if (i == extensions.length()) return;
            if (extensions.charAt(i) != ';') throw new ParseException("Invalid chunk extension separator", position);
            i++;
        }
    }

    private static int skipOWS(String value, int offset) {
        while (offset < value.length()) {
            char c = value.charAt(offset);
            if (c != ' ' && c != '\t') return offset;
            offset++;
        }
        return offset;
    }

    private static int readToken(String value, int offset, int position, String name) throws ParseException {
        int start = offset;
        while (offset < value.length() && ParseUtils.isTChar(value.charAt(offset))) {
            offset++;
        }
        if (offset == start) throw new ParseException("Missing " + name, position);
        return offset;
    }

    private static int readQuotedString(String value, int offset, int position) throws ParseException {
        boolean escaped = false;
        while (offset < value.length()) {
            char c = value.charAt(offset++);
            if (escaped) {
                if (!isQuotedPairChar(c)) throw new ParseException("Invalid quoted-pair in chunk extension", position);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return offset;
            } else if (!isQuotedText(c)) {
                throw new ParseException("Invalid quoted text in chunk extension", position);
            }
        }
        throw new ParseException("Unterminated chunk extension quoted-string", position);
    }

    private static boolean isQuotedText(char c) {
        return c == '\t' || c == ' ' || c == 0x21 || (c >= 0x23 && c <= 0x5B) || (c >= 0x5D && c <= 0x7E);
    }

    private static boolean isQuotedPairChar(char c) {
        return c == '\t' || c == ' ' || (c >= 0x21 && c <= 0x7E);
    }


    private static String consumeAscii(ByteArrayOutputStream baos) {
        var v = baos.toString(StandardCharsets.US_ASCII);
        baos.reset();
        return v;
    }

    private static HttpVersion consumeHttpVersion(ByteArrayOutputStream baos) {
        HttpVersion v = HttpVersion.fromVersion(consumeAscii(baos));
        if (v != null) {
            return v;
        }
        throw new HttpException(HttpStatus.HTTP_VERSION_NOT_SUPPORTED_505);
    }
}
