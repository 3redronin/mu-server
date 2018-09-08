package io.muserver;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

import static java.util.Collections.emptyList;

class MultipartRequestBodyParser {

    private enum State {PREAMBLE, ENCAP, CLOSE, EPILOGUE}

    private enum EncapState {HEADERS, BODY}

    private final Charset bodyCharset;
    private final String boundary;
    private State state = State.PREAMBLE;
    private EncapState encapState = EncapState.HEADERS;
    private final MultivaluedMap<String, String> formParams = new MultivaluedHashMap<>();

    MultipartRequestBodyParser(Charset bodyCharset, String boundary) {
        this.bodyCharset = bodyCharset;
        this.boundary = boundary;
    }

    public void parse(InputStream inputStream) throws IOException {


        BoundariedInputStream bis = new BoundariedInputStream(inputStream, "--" + boundary + "\r\n");

        byte[] buffer = new byte[8192];
        int read;

        while (bis.read(buffer) > -1) {
            // eat up the preamble
        }
        bis.changeBoundary("--" + boundary + "\r\n");
        state = State.ENCAP;


        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
        int offset = 0;

        for (int j = 0; j < 2; j++) {

            bis = bis.continueNext();
            MediaType partType = MediaType.TEXT_PLAIN_TYPE;
            String formName = null;

            while ((read = bis.read(buffer)) > -1) {

                byte lastB = 0;
                long partLen = -1;
                long bodyRead = 0;

                int i = 0;
                headerparser:
                for (i = 0; i < read; i++) {
                    if (state != State.ENCAP || encapState != EncapState.BODY) {
                        byte b = buffer[i];
                        if (lastB == '\r' && b == '\n') {
                            lineBuffer.write(buffer, offset, i - offset - 1);
                            String line = lineBuffer.toString(bodyCharset.name());
                            lineBuffer.reset();
                            if (state == State.ENCAP) {

                                if (encapState == EncapState.HEADERS) {
                                    if (line.isEmpty()) {
                                        encapState = EncapState.BODY;
                                        bodyRead = 0;
                                        i++;
                                        break headerparser;
                                    } else {
                                        System.out.println("line = " + line);
                                        String[] bits = line.split(":", 2);
                                        String headerName = bits[0].trim().toLowerCase();
                                        if (headerName.equals("content-disposition")) {
                                            HeaderValue disposition = HeaderValue.fromString(bits[1]).get(0);
                                            switch (disposition.value()) {
                                                case "form-data": {
                                                    formName = disposition.parameters().get("name");
                                                    break;
                                                }
                                            }
                                        } else if (headerName.equals("content-type")) {
                                            partType = MediaTypeParser.fromString(bits[1]);
                                        } else if (headerName.equals("content-length")) {
                                            partLen = Long.parseLong(bits[1].trim());
                                        }
                                    }

                                }
                            }
                            offset = i + 1;
                        }
                        lastB = b;
                    }
                }
                if (encapState == EncapState.BODY) {
                    bodyBuffer.write(buffer, i, read - i);
                }
            }
            // finished reading
            String partCharset = partType.getParameters().getOrDefault("charset", "UTF-8");
            String formValue = bodyBuffer.toString(partCharset);
            formParams.putSingle(formName, formValue);

        }

    }


    List<String> formValue(String name) {
        return this.formParams.getOrDefault(name, emptyList());
    }
}
