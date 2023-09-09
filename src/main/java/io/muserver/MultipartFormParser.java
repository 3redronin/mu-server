package io.muserver;

import io.muserver.rest.MuRuntimeDelegate;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.muserver.ParseUtils.*;
import static java.util.Collections.emptyList;

class MultipartFormParser implements MuForm, RequestBodyListener {
    static {
        MuRuntimeDelegate.ensureSet();
    }


    MultipartFormParser(Path fileUploadDir, Charset bodyCharset, String boundary) {
        if (boundary.length() > 70) {
            throw new BadRequestException("Multi part form param boundary value is too long");
        }
        this.fileUploadDir = fileUploadDir;
        this.bodyCharset = bodyCharset;
        this.boundary = boundary;
        this.charBuffer = CharBuffer.allocate(boundary.length() + 6);
    }

    private enum State {
        INITIAL,
        BOUNDARY,
        BOUNDARY_HEADERS,
        ENTRY_DATA,
        BOUNDARY_END,
        BODY_END,
        ERROR,
    }

    private enum HeadersState {
        INITIAL,
        NAME,
        NAME_DONE,
        VALUE,
        VALUE_DONE
    }

    private final Path fileUploadDir;
    private final Charset bodyCharset;
    private final String boundary;
    private volatile State state = State.INITIAL;
    private volatile HeadersState headersState;
    private volatile Throwable error = null;
    private final CharBuffer charBuffer;
    private final MuHeaders curHeaders = new MuHeaders();

    private final MultivaluedMap<String, String> formParams = new MultivaluedHashMap<>();
    private final MultivaluedMap<String, UploadedFile> fileParams = new MultivaluedHashMap<>();

    private volatile MediaType type;
    private volatile ParameterizedHeaderWithValue contentDisposition;
    private volatile char curHeader; // only two headers supported: 'd' for 'content-disposition'; 't' for 'content-type'

    @Override
    public RequestParameters params() {
        return this;
    }

    @Override
    public List<UploadedFile> uploads(String name) {
        List<UploadedFile> list = fileParams.get(name);
        return list == null ? emptyList() : list;
    }

    @Override
    public Map<String, List<String>> all() {
        return formParams;
    }

    @Override
    public void onDataReceived(ByteBuffer buffer, DoneCallback doneCallback) throws Exception {

        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            switch (state) {
                case INITIAL -> {
                    var c = (char) b; //ascii, so it fits
                    if (isOWS(c) && charBuffer.isEmpty()) {
                        // skip starting whitespace
                    } else {
                        charBuffer.put(c);
                        int delimLength = boundary.length() + 4;
                        if (charBuffer.position() == delimLength) {
                            charBuffer.flip();
                            String contents = clearBuffer();
                            if (contents.equals("--" + boundary + "\r\n")) {
                                changeStateToBoundaryHeaders();
                            } else if (contents.equals("--" + boundary + "--")) {
                                state = State.BODY_END;
                            } else {
                                err(doneCallback, "Invalid request body in multi-part form: first line is " + contents + " and boundary is " + boundary);
                                return;
                            }
                        }
                    }
                }
                case BOUNDARY_HEADERS -> {
                    var c = (char) b; //ascii, so it fits
                    switch (headersState) {
                        case INITIAL -> {
                            if (isTChar(c)) {
                                headersState = HeadersState.NAME;
                                charBuffer.put(c);
                            } else if (c == '\r') {
                                if (charBuffer.position() != 0) {
                                    err(doneCallback, "Invalid characters in multipart form data");
                                    return;
                                }
                                charBuffer.put('\r');
                            } else if (c == '\n') {
                                charBuffer.flip();
                                if (charBuffer.limit() != 1 && charBuffer.get() != '\r') {
                                    err(doneCallback, "Newline character without carriage return in section headers");
                                } else {
                                    // we come to the end of without leaving the INITIAL state - there is no header!
                                    err(doneCallback, "No content-disposition header for form section");
                                }
                                return;
                            } else if (!isOWS(c)) {
                                err(doneCallback, "Invalid character while parsing multi part form section headers");
                                return;
                            }
                        }
                        case NAME -> {
                            if (isTChar(c)) {
                                headersState = HeadersState.NAME;
                                charBuffer.append(c);
                            } else if (c == ':') {
                                headersState = HeadersState.NAME_DONE;
                                var header = clearBuffer().toLowerCase();
                                switch (header) {
                                    case "content-disposition" -> curHeader = 'd';
                                    case "content-type" -> curHeader = 't';
                                    default -> curHeader = 0;
                                }
                            } else if (!isOWS(c)) {
                                err(doneCallback, "Invalid character while reading section header name: " + b);
                                return;
                            }
                        }
                        case NAME_DONE -> {
                            if (isVChar(c)) {
                                charBuffer.put(c);
                                headersState = HeadersState.VALUE;
                            } else if (!isOWS(c)) {
                                err(doneCallback, "Invalid character between name and value in section: " + b);
                                return;
                            }
                        }
                        case VALUE -> {
                            if (isVChar(c) || isOWS(c)) {
                                charBuffer.put(c);
                            } else if (c == '\n' && !charBuffer.isEmpty() && charBuffer.get(charBuffer.position() -1) == '\r') {
                                var val = clearBuffer().trim();

                                headersState = HeadersState.VALUE_DONE;
                            } else {
                                err(doneCallback, "Invalid character reading value: " + b);
                                return;
                            }
                        }
                        case VALUE_DONE -> {
                        }
                    }
                }
                case BODY_END -> {
                    err(doneCallback, "Invalid multi-part request body - overflow");
                    return;
                }
            }
        }

        doneCallback.onComplete(null);
    }

    private String clearBuffer() {
        String s = charBuffer.toString();
        charBuffer.clear();
        return s;
    }

    private static void err(DoneCallback doneCallback, String message) throws Exception {
        doneCallback.onComplete(new BadRequestException(message));
    }

    private void changeStateToBoundaryHeaders() {
        charBuffer.clear();
        headersState = HeadersState.INITIAL;
        curHeaders.clear();
        state = State.BOUNDARY_HEADERS;
    }

    @Override
    public void onComplete() {

    }

    @Override
    public void onError(Throwable t) {
        this.error = t;
        state = State.ERROR;
    }


}
