package io.muserver;

import io.muserver.rest.MuRuntimeDelegate;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static io.muserver.ParseUtils.*;

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
        this.charBuffer = new StringBuilder(boundary.length() + 6);
        byte[] boundaryEndBytes = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        this.bodyBuffer = new BoundaryCheckingOutputStream(boundaryEndBytes);
    }

    private enum State {
        INITIAL,
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
        VALUE_DONE,
        HEADERS_DONE
    }

    private final Path fileUploadDir;
    private final Charset bodyCharset;
    private final String boundary;
    private volatile State state = State.INITIAL;
    private volatile HeadersState headersState;
    private volatile Throwable error = null;
    private final StringBuilder charBuffer;
    private final BoundaryCheckingOutputStream bodyBuffer;
    private final MuHeaders curHeaders = new MuHeaders();

    private final MultivaluedMap<String, String> formParams = new MultivaluedHashMap<>();
    private final Map<String, List<UploadedFile>> fileParams = new HashMap<>();

    private volatile MediaType type;
    private volatile ParameterizedHeaderWithValue contentDisposition;
    private volatile char curHeader; // only two headers supported: 'd' for 'content-disposition'; 't' for 'content-type'

    private void throwIfError() {
        if (error != null) {
            var ex = error instanceof IOException ioe ? ioe : new IOException("Error reading form body", error);
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public RequestParameters params() {
        throwIfError();
        return this;
    }

    @Override
    public Map<String, List<UploadedFile>> uploadedFiles() {
        throwIfError();
        return Collections.unmodifiableMap(fileParams);
    }

    @Override
    public Map<String, List<String>> all() {
        throwIfError();
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
                        charBuffer.append(c);
                        int delimLength = boundary.length() + 4;
                        if (charBuffer.length() == delimLength) {
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
                                switchToHeaderNameState(c);
                            } else if (c == '\r') {
                                if (!charBuffer.isEmpty()) {
                                    err(doneCallback, "Invalid characters in multipart form data");
                                    return;
                                }
                                charBuffer.append('\r');
                            } else if (c == '\n') {
                                if (charBuffer.length() != 1 && charBuffer.charAt(0) != '\r') {
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
                                switchToHeaderNameState(c);
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
                                charBuffer.append(c);
                                headersState = HeadersState.VALUE;
                            } else if (!isOWS(c)) {
                                err(doneCallback, "Invalid character between name and value in section: " + b);
                                return;
                            }
                        }
                        case VALUE -> {
                            if (isVChar(c) || isOWS(c) || c == '\r') {
                                charBuffer.append(c);
                            } else if (c == '\n' && !charBuffer.isEmpty() && charBuffer.charAt(charBuffer.length() - 1) == '\r') {
                                if (curHeader == 'd') {
                                    contentDisposition = ParameterizedHeaderWithValue.fromString(clearBuffer().trim())
                                        .stream().filter(h -> h.value().equals("form-data")).findFirst().orElse(null);
                                    if (contentDisposition == null || !contentDisposition.parameters().containsKey("name")) {
                                        err(doneCallback, "A section is missing a content disposition header with a name attribute");
                                        return;
                                    }
                                } else if (curHeader == 't') {
                                    type = MediaTypeParser.fromString(clearBuffer().trim());
                                } else {
                                    charBuffer.setLength(0);
                                }
                                headersState = HeadersState.VALUE_DONE;
                            } else {
                                err(doneCallback, "Invalid character reading value: " + b);
                                return;
                            }
                        }
                        case VALUE_DONE -> {

                            if (isTChar(c)) {
                                switchToHeaderNameState(c);
                            } else if (c == '\r' && charBuffer.isEmpty()) {
                                charBuffer.append(c);
                            } else if (c == '\n' && charBuffer.length() == 1 && charBuffer.charAt(0) == '\r') {
                                headersState = HeadersState.HEADERS_DONE;
                                charBuffer.setLength(0);
                                state = State.ENTRY_DATA;
                            }

                        }
                        case HEADERS_DONE -> {
                            err(doneCallback, "Did not expect more header characters when already done");
                            return;
                        }
                    }
                }
                case ENTRY_DATA -> {
                    var cd = contentDisposition;
                    if (cd == null) {
                        err(doneCallback, "Multipart section does not have a content-disposition header");
                        return;
                    }
                    String filename = cd.parameter("filename");
                    var isFile = filename != null;
                    bodyBuffer.write(b);
                    if (bodyBuffer.endsWithBoundary()) {
                        var sectionCharset = type == null ? null : type.getParameters().get("charset");
                        var paramName = cd.parameter("name");
                        if (isFile) {
                            var fileType = type != null ? type : MediaType.APPLICATION_OCTET_STREAM_TYPE;
                            var path = Path.of(filename);
                            var uploadedFile = new MuUploadedFile2(path, fileType.toString(), filename);
                            fileParams.putIfAbsent(paramName, new ArrayList<>());
                            fileParams.get(paramName).add(uploadedFile);
                        } else {
                            var toUse = sectionCharset != null ? Charset.forName(sectionCharset) : bodyCharset;
                            var valueBytes = bodyBuffer.consume();
                            var valueAsString = new String(valueBytes, toUse);
                            formParams.add(paramName, valueAsString);
                        }
                        state = State.BOUNDARY_END;
                    }
                }
                case BOUNDARY_END -> {
                    var c = (char) b;
                    if (charBuffer.isEmpty()) {
                        if (c == '-' || c == '\r') {
                            charBuffer.append(c);
                        } else {
                            err(doneCallback, "Invalid character after a boundary end: " + b);
                            return;
                        }
                    } else {
                        var prev = charBuffer.charAt(0);
                        if (prev == '-' && c == '-') {
                            state = State.BODY_END;
                        } else if (prev == '\r' && c == '\n') {
                            changeStateToBoundaryHeaders();
                        } else {
                            err(doneCallback, "Invalid characters after boundary: " + ((int) prev) + " and " + b);
                            return;
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

    private void switchToHeaderNameState(char c) {
        headersState = HeadersState.NAME;
        charBuffer.append(c);
    }

    private String clearBuffer() {
        String s = charBuffer.toString();
        charBuffer.setLength(0);
        return s;
    }

    private static void err(DoneCallback doneCallback, String message) throws Exception {
        doneCallback.onComplete(new BadRequestException(message));
    }

    private void changeStateToBoundaryHeaders() {
        charBuffer.setLength(0);
        headersState = HeadersState.INITIAL;
        curHeaders.clear();
        state = State.BOUNDARY_HEADERS;
        type = null;
        contentDisposition = null;
    }

    @Override
    public void onComplete() {
        if (state != State.BODY_END) {
            throw new IllegalStateException("The request body ended but the multipart form was completed; state was " + state);
        }
        this.charBuffer.setLength(0);
        this.bodyBuffer.reset();
    }

    @Override
    public void onError(Throwable t) {
        this.error = t;
        state = State.ERROR;
    }


    private static class BoundaryCheckingOutputStream extends ByteArrayOutputStream {

        private final byte[] boundaryEnd;

        private BoundaryCheckingOutputStream(byte[] boundaryEnd) {
            this.boundaryEnd = boundaryEnd;
        }

        public boolean endsWithBoundary() {
            int dif = this.size() - boundaryEnd.length;
            if (dif < 0) return false;
            for (int i = 0; i < boundaryEnd.length; i++) {
                if (this.buf[dif + i] != boundaryEnd[i]) {
                    return false;
                }
            }
            return true;
        }

        public byte[] consume() {
            var bytes = Arrays.copyOf(buf, size() - boundaryEnd.length);
            this.reset();
            return bytes;
        }
    }

}
