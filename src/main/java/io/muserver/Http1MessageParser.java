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
        if (limit == -1) return EOFMsg;
        while (true) {
            if (!hasRemaining()) {
                position = 0;
                limit = source.read(readBuffer);
                if (limit == -1) {
                    if (state == ParseState.UNSPECIFIED_BODY) {
                        return EndOfBodyBit;
                    }
                    return EOFMsg;
                }
            }
            while (hasRemaining()) {
                byte b = readBuffer[position];
                switch (state) {
                    case REQUEST_START: {
                        if (isUpperCase(b)) {
                            requestQueue.offer((HttpRequestTemp) exchange);
                            state = ParseState.METHOD;
                            append(buffer, b);
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case METHOD: {
                        if (isUpperCase(b)) {
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
                            var bad = reject != null && reject.status() == HttpStatus.URI_TOO_LONG_414;
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
                            state = ParseState.HEADER_START;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case RESPONSE_START: {
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
                            state = ParseState.HEADER_START;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case HEADER_START: {
                        curHeadersLen = 1;
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
                        }
                        break;
                    }

                    case HEADER_NAME_ENDED: {
                        var isOkay = onHeaderChar();
                        if (isOWS(b)) {
                            // skip it
                        } else if (isVChar(b)) {
                            if (isOkay) {
                                append(buffer, b);
                            }
                            state = ParseState.HEADER_VALUE;
                        } else if (b == CR) {
                            // an empty value - ignore it
                            state = ParseState.HEADER_VALUE_ENDING;
                        } else {
                            throw new ParseException("Invalid header value " + b, position);
                        }
                        break;
                    }

                    case HEADER_VALUE: {
                        var isOkay = onHeaderChar();
                        if (isVChar(b) || isOWS(b)) {
                            if (isOkay) {
                                append(buffer, b);
                            }
                        } else if (b == CR) {
                            state = ParseState.HEADER_VALUE_ENDING;
                        }
                        break;
                    }

                    case HEADER_VALUE_ENDING: {
                        var isOkay = onHeaderChar();
                        if (b == LF) {
                            if (isOkay) {
                                var value = consumeAscii(buffer).trim();
                                if (!value.isEmpty()) {
                                    exchange.headers().add(headerName, value);
                                }
                            }
                            state = ParseState.HEADER_START;
                        } else throw new ParseException("No LF after CR at " + state, position);
                        break;
                    }

                    case HEADERS_ENDING: {
                        if (b == LF) {
                            var exc = exchange;
                            var body = exc.bodyTransferSize();
                            exc.setBodySize(body);
                            switch (body.type()) {
                                case FIXED_SIZE: {
                                    long len = body.size();
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
                            remainingBytesToProxy = Long.parseLong(consumeAscii(buffer), 16);
                        }
                        break;
                    }

                    case CHUNK_EXTENSIONS: {
                        if (isVChar(b) || isOWS(b)) {
                            // todo: only allow valid extension characters
                        } else if (b == CR) {
                            state = ParseState.CHUNK_HEADER_ENDING;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case CHUNK_HEADER_ENDING: {
                        if (b == LF) {
                            // remainingBytesToProxy has the chunk size in it
                            state = (remainingBytesToProxy == 0L) ? ParseState.LAST_CHUNK : ParseState.CHUNK_DATA;
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

                    case LAST_CHUNK: {
                        if (b == CR) {
                            state = ParseState.CHUNKED_BODY_ENDING;
                        } else if (isTChar(b)) {
                            append(buffer, b);
                            state = ParseState.TRAILERS;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                        break;
                    }

                    case CHUNKED_BODY_ENDING: {
                        if (b == LF) {
                            position++;
                            onMessageEnded();
                            return EndOfBodyBit;
                        } else throw new ParseException("state=" + state + " b=" + b, position);
                    }

                    case TRAILERS: {
                        if (isOWS(b) || isVChar(b) || b == CR) {
                            append(buffer, b);
                        } else if (b == LF) {
                            append(buffer, b);
                            var trailerPart = buffer.toString(StandardCharsets.US_ASCII);
                            if (trailerPart.endsWith("\r\n\r\n")) {
                                buffer.reset();
                                onMessageEnded();
                                // TODO: pass back the trailers
                                return EndOfBodyBit;
                            }
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

    private boolean onHeaderChar() {
        curHeadersLen++;
        if (curHeadersLen <= maxHeadersLength) return true;
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
        HEADERS_ENDING,
        FIXED_SIZE_BODY,
        UNSPECIFIED_BODY,
        CHUNK_START,
        CHUNK_SIZE,
        CHUNK_EXTENSIONS,
        CHUNK_HEADER_ENDING,
        CHUNK_DATA,
        LAST_CHUNK,
        CHUNKED_BODY_ENDING,
        TRAILERS,
        WEBSOCKET,
        CHUNK_DATA_READ,
        CHUNK_DATA_ENDING,
    }


    @Override
    public String toString() {
        return getClass().getSimpleName() + " "  + this.state;
    }

    private void append(ByteArrayOutputStream baos, byte b) {
        baos.write(b);
        if (baos.size() > maxBufferSize) throw new IllegalStateException("Buffer is " + baos.size() + " bytes");
    }

    private static boolean isVChar(byte b) { return b >= (byte)0x21 && b <= (byte)0x7E; }

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


