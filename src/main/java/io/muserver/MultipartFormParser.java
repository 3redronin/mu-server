package io.muserver;

import io.muserver.rest.MuRuntimeDelegate;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static io.muserver.ParseUtils.*;

class MultipartFormParser implements MuForm, RequestBodyListener, Closeable {
    static {
        MuRuntimeDelegate.ensureSet();
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
    private final BoundaryCheckingOutputStream bodyBuffer;
    private final MuHeaders curHeaders = new MuHeaders();

    private final MultivaluedMap<String, String> formParams = new MultivaluedHashMap<>();
    private final Map<String, List<UploadedFile>> fileParams = new HashMap<>();

    private volatile MediaType type;
    private volatile ParameterizedHeaderWithValue contentDisposition;
    private volatile char curHeader; // only two headers supported: 'd' for 'content-disposition'; 't' for 'content-type'
    private volatile AsynchronousFileChannel fileChannel;
    private volatile Path tempFile;

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
    public void onDataReceived(ByteBuffer buffer, DoneCallback doneCallback) {
        try {
            parse(buffer, error -> {
                if (error == null && buffer.hasRemaining()) {
                    System.out.println("Still " + buffer.remaining() + " to write");
                    onDataReceived(buffer, doneCallback);
                } else {
                    doneCallback.onComplete(error);
                }
            });
        } catch (Exception e) {
            try {
                close();
            } catch (IOException ignored) {
            }
            doneCallback.onComplete(e);
        }
    }

    private void parse(ByteBuffer buffer, DoneCallback doneCallback) throws Exception {
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            switch (state) {
                case INITIAL -> {
                    var c = (char) b; //ascii, so it fits
                    // anything before '--boundary' should be discarded so we keep track of that
                    // once we hit '--boundary', the next two chars are either '\r\n' (for a section)
                    // or '--' (for end of body). So at '--boundary' we stop comparing, get the next
                    // two chars, and then see what's what.
                    var dashDashBoundaryLength = 2 + boundary.length();
                    System.out.println(charBuffer + " len=" + charBuffer.length() + " / " + dashDashBoundaryLength);
                    if (charBuffer.length() < dashDashBoundaryLength) {
                        var expected = bodyBuffer.boundaryEnd[charBuffer.length() + 2]; // boundary end starts with 2 chars (\r\n) that are not expected here
                        if (c == expected) {
                            charBuffer.append(c);
                        } else {
                            charBuffer.setLength(0);
                        }
                    } else {
                        charBuffer.append(c);
                        if (charBuffer.length() == dashDashBoundaryLength + 2) {
                            String contents = clearBuffer();
                            if (contents.equals("--" + boundary + "\r\n")) {
                                changeStateToBoundaryHeaders();
                            } else if (contents.equals("--" + boundary + "--")) {
                                state = State.BODY_END;
                            } else {
                                throw new BadRequestException("Invalid request body in multi-part form: first line is " + contents + " and boundary is " + boundary);
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
                                    throw new BadRequestException("Invalid characters in multipart form data");
                                }
                                charBuffer.append('\r');
                            } else if (c == '\n') {
                                if (charBuffer.length() != 1 && charBuffer.charAt(0) != '\r') {
                                    throw new BadRequestException("Newline character without carriage return in section headers");
                                } else {
                                    // we come to the end of without leaving the INITIAL state - there is no header!
                                    throw new BadRequestException("No content-disposition header for form section");
                                }
                            } else if (!isOWS(c)) {
                                throw new BadRequestException("Invalid character while parsing multi part form section headers");
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
                                throw new BadRequestException("Invalid character while reading section header name: " + b);
                            }
                        }
                        case NAME_DONE -> {
                            if (isVChar(c)) {
                                charBuffer.append(c);
                                headersState = HeadersState.VALUE;
                            } else if (!isOWS(c)) {
                                throw new BadRequestException("Invalid character between name and value in section: " + b);
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
                                        throw new BadRequestException("A section is missing a content disposition header with a name attribute");
                                    }
                                } else if (curHeader == 't') {
                                    type = MediaTypeParser.fromString(clearBuffer().trim());
                                } else {
                                    charBuffer.setLength(0);
                                }
                                headersState = HeadersState.VALUE_DONE;
                            } else {
                                throw new BadRequestException("Invalid character reading value: " + b);
                            }
                        }
                        case VALUE_DONE -> {

                            if (isTChar(c)) {
                                switchToHeaderNameState(c);
                            } else if (c == '\r' && charBuffer.isEmpty()) {
                                charBuffer.append(c);
                            } else if (c == '\n' && charBuffer.length() == 1 && charBuffer.charAt(0) == '\r') {
                                headersState = HeadersState.HEADERS_DONE;
                                switchToEntryData();
                            }

                        }
                        case HEADERS_DONE -> {
                            throw new BadRequestException("Did not expect more header characters when already done");
                        }
                    }
                }
                case ENTRY_DATA -> {
                    var isFile = fileChannel != null;

                    bodyBuffer.write(b);
                    if (bodyBuffer.endsWithBoundary()) {
                        var sectionCharset = type == null ? null : type.getParameters().get("charset");
                        var cd = this.contentDisposition;
                        var paramName = cd.parameter("name");
                        if (isFile) {
                            var filename = cd.parameter("filename");
                            var fileType = type != null ? type : MediaType.APPLICATION_OCTET_STREAM_TYPE;
                            var uploadedFile = new MuUploadedFile2(tempFile, fileType.toString(), filename);
                            fileParams.putIfAbsent(paramName, new ArrayList<>());
                            fileParams.get(paramName).add(uploadedFile);
                        } else {
                            var toUse = sectionCharset != null ? Charset.forName(sectionCharset) : bodyCharset;
                            var valueBytes = bodyBuffer.consume();
                            var valueAsString = new String(valueBytes, toUse);
                            formParams.add(paramName, valueAsString);
                        }
                        type = null;
                        contentDisposition = null;
                        curHeader = 0;
                        state = State.BOUNDARY_END;
                        if (fileChannel != null) {
                            bodyBuffer.flushTo(fileChannel, flushError -> {
                                try {
                                    fileChannel.close();
                                    fileChannel = null;
                                    tempFile = null;
                                    doneCallback.onComplete(flushError);
                                } catch (IOException e) {
                                    doneCallback.onComplete(error);
                                } finally {
                                    fileChannel = null;
                                }
                            });
                            return; // stop processing here to return to the calling method, so that we wait until the async operation is finished
                        }
                    }
                }
                case BOUNDARY_END -> {
                    var c = (char) b;
                    if (charBuffer.isEmpty()) {
                        if (c == '-' || c == '\r') {
                            charBuffer.append(c);
                        } else {
                            throw new BadRequestException("Invalid character after a boundary end: " + b);
                        }
                    } else {
                        var prev = charBuffer.charAt(0);
                        if (prev == '-' && c == '-') {
                            state = State.BODY_END;
                        } else if (prev == '\r' && c == '\n') {
                            changeStateToBoundaryHeaders();
                        } else {
                            throw new BadRequestException("Invalid characters after boundary: " + ((int) prev) + " and " + b);
                        }
                    }
                }
                case BODY_END -> {
                    throw new BadRequestException("Invalid multi-part request body - overflow");
                }
            }

            // we have enough file data in memory - flush to disk and stop processing until the write callback is completed
            if (fileChannel != null && bodyBuffer.size() > 8192) {
                bodyBuffer.flushTo(fileChannel, doneCallback);
                return;
            }
        }


        doneCallback.onComplete(null);
    }

    private void switchToEntryData() throws Exception {
        charBuffer.setLength(0);
        bodyBuffer.reset();
        state = State.ENTRY_DATA;
        var cd = contentDisposition;
        if (cd == null) {
            throw new BadRequestException("Multipart section does not have a content-disposition header");
        }
        if (cd.parameters().containsKey("filename")) {
            tempFile = Files.createTempFile(fileUploadDir, "muserverupload", ".tmp");
            fileChannel = AsynchronousFileChannel.open(tempFile, StandardOpenOption.WRITE);
        }
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

    @Override
    public void close() throws IOException {
        if (fileChannel != null) {
            fileChannel.close();
            fileChannel = null;
        }
        if (tempFile != null) {
            MuUploadedFile2.tryToDelete(tempFile);
            tempFile = null;
        }
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

        final byte[] boundaryEnd;

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

        public void flushTo(AsynchronousFileChannel fileChannel, DoneCallback doneCallback) throws IOException {
            var toWrite = ByteBuffer.wrap(buf, 0, size() - boundaryEnd.length);
            long size = fileChannel.size();
            fileChannel.write(toWrite, size, null, new CompletionHandler<>() {
                @Override
                public void completed(Integer result, Object attachment) {
                    if (toWrite.hasRemaining()) {
                        fileChannel.write(toWrite, size + result, null, this);
                    } else {
                        compact();
                        doneCallback.onComplete(null);
                    }
                }

                @Override
                public void failed(Throwable exc, Object attachment) {
                    doneCallback.onComplete(exc);
                }
            });
        }

        /**
         * Discards all data before the last 'n' bytes (the length of the boundary) and moves those
         * remaining bytes to the beginning of the array.
         */
        public void compact() {
            byte[] ending = new byte[boundaryEnd.length];
            System.arraycopy(buf, size() - boundaryEnd.length, ending, 0, ending.length);
            reset();
            write(ending, 0, ending.length);
        }

    }

    private static boolean isFieldNameChar(char c) {
        return c >= 33 && c <= 126 && c != ':';
    }
    private static boolean isBChar(char c) {
        return c == ' ' || isBCharNoSpace(c);
    }
    private static boolean isBCharNoSpace(char c) {
        // DIGIT / ALPHA / "'" / "(" / ")" / "+"  / "_" / "," / "-" / "." / "/" / ":" / "=" / "?"
        //                  39 || 40 || 41 || 43 || 45 || 44 || 46 || 47 || 58 || 61 || 63
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            c == 39 || c == 40 || c==41 || (c >= '+' && c <= ':') || c == 63;
    }

}
