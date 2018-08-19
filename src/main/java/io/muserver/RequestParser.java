package io.muserver;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

class RequestParser {

    private State state = State.RL_METHOD;

    private final RequestListener requestListener;
    private StringBuffer cur = new StringBuffer();

    private Method method;
    private URI requestUri;
    private String protocol;
    private final MuHeaders headers = new MuHeaders();
    private String curHeader;
    private List<String> curVals;
    GrowableByteBufferInputStream body;
    private Charset bodyCharset = StandardCharsets.ISO_8859_1;
    private long bodyLength = -1;
    private long bodyBytesRead = 0;

    boolean complete() {
        return state == State.COMPLETE;
    }

    RequestParser(RequestListener requestListener) {
        this.requestListener = requestListener;
    }

    private enum State {
        RL_METHOD, RL_URI, RL_PROTO, RL_ENDING, H_NAME, H_VALUE, FIXED_BODY, CHUNKED_BODY, COMPLETE
    }

    void offer(ByteBuffer bb) throws InvalidRequestException {
        if (state == State.COMPLETE) {
            throw new InvalidRequestException(400, "Request body too long", "More request was found even though no more was expected.");
        } else if (state == State.FIXED_BODY) {
            int size = bb.limit();
            bodyBytesRead += size;
            ByteBuffer copy = ByteBuffer.allocate(size);
            copy.put(bb);
            copy.flip();
            body.handOff(copy);

            if (bodyBytesRead == bodyLength) {
                body.close();
                state = State.COMPLETE;
            } else if (bodyBytesRead > bodyLength) {
                throw new InvalidRequestException(400, "Request body too long", "The client declared a body length of " + bodyLength + " but has already sent " + bodyBytesRead);
            }

            return;
        }

        while (bb.hasRemaining()) {

            byte c = bb.get();
            if (c == ' ') {
                switch (state) {
                    case RL_METHOD:
                        method = Method.valueOf(cur.toString());
                        state = State.RL_URI;
                        cur.setLength(0);
                        break;
                    case RL_URI:
                        requestUri = URI.create(cur.toString());
                        state = State.RL_PROTO;
                        cur.setLength(0);
                        break;
                    case H_VALUE:
                        if (cur.length() > 0) {
                            append(c);
                        }
                        break;
                    default:
                        throw new IllegalStateException("Shouldn't have a space while in " + state);
                }
            } else if (c == '\r') {
                switch (state) {
                    case RL_PROTO:
                        this.protocol = cur.toString();
                        switch (protocol) {
                            case "HTTP/1.0":
                            case "HTTP/1.1":
                                this.state = State.RL_ENDING;
                                cur.setLength(0);
                                break;
                            default:
                                throw new MuException("Unsupported HTTP protocol " + protocol);
                        }
                    case H_NAME:
                        if (cur.length() > 0) {
                            throw new InvalidRequestException(400, "A header name included a carriage return character", "Value was " + cur);
                        }
                        break;
                    case H_VALUE:


                        String val = cur.toString().trim();
                        switch (curHeader) {
                            case "content-length":
                                long prev = this.bodyLength;
                                try {
                                    this.bodyLength = Long.parseLong(val);
                                } catch (NumberFormatException e) {
                                    throw new InvalidRequestException(400, "Invalid content-length header", "Header was " + cur);
                                }
                                if (prev != -1 && prev != this.bodyLength) {
                                    throw new InvalidRequestException(400, "Multiple content-length headers", "First was " + prev + " and then " + bodyLength);
                                }
                                break;
                        }
                        curVals.add(val);
                        cur.setLength(0);
                        break;
                    default:
                        throw new InvalidRequestException(400, "Unexpected CR", "State is " + state + " and accumulated value was " + cur);
                }
            } else if (c == '\n') {
                switch (state) {
                    case RL_ENDING:
                        state = State.H_NAME;
                        break;
                    case H_NAME:
                        if (cur.length() > 0) {
                            throw new InvalidRequestException(400, "A header name included a line feed character", "Value was " + cur);
                        }
                        cur.setLength(0);

                        boolean hasContentLength = bodyLength > -1;
                        boolean hasTransferEncoding = headers.contains("transfer-encoding");
                        if (hasContentLength || hasTransferEncoding) {
                            if (hasContentLength && hasTransferEncoding) {
                                throw new InvalidRequestException(400, "A request cannot have both transfer encoding and content length", "Headers were " + headers);
                            }
                            body = new GrowableByteBufferInputStream();
                            state = hasContentLength ? State.FIXED_BODY : State.CHUNKED_BODY;
                        } else {
                            state = State.COMPLETE;
                        }

                        requestListener.onHeaders(method, requestUri, protocol, headers);
                        break;
                    case H_VALUE:
                        if (cur.length() > 0) {
                            throw new InvalidRequestException(400, "A newline was inside a header value", "Header value was " + cur);
                        }
                        state = State.H_NAME;
                        break;
                    default:
                        append(c);
                }
            } else if (c == ':') {
                switch (state) {
                    case H_NAME:
                        String header = cur.toString();
                        this.curHeader = header.toLowerCase();
                        if (headers.contains(header)) {
                            curVals = headers.getAll(header);
                        } else {
                            curVals = new ArrayList<>();
                            headers.put(header, curVals);
                        }
                        state = State.H_VALUE;
                        cur.setLength(0);
                        break;
                    default:
                        append(c);
                }
            } else {
                append(c);
            }
        }
    }

    private void append(byte c) {
        cur.append((char) c);
    }

    interface RequestListener {
        void onHeaders(Method method, URI uri, String proto, MuHeaders headers);
    }

    static class InvalidRequestException extends Exception {
        final int responseCode;
        final String clientMessage;
        final String privateDetails;

        InvalidRequestException(int responseCode, String clientMessage, String privateDetails) {
            super(responseCode + " " + clientMessage + " - " + privateDetails);
            this.responseCode = responseCode;
            this.clientMessage = clientMessage;
            this.privateDetails = privateDetails;
        }
    }
}
