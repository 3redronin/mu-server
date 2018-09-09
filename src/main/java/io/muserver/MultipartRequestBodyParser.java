package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log = LoggerFactory.getLogger(MultipartRequestBodyParser.class);

    private enum PartState {HEADERS, BODY}

    private final Charset bodyCharset;
    private final String boundary;
    private PartState partState = PartState.HEADERS;
    private final MultivaluedMap<String, String> formParams = new MultivaluedHashMap<>();

    MultipartRequestBodyParser(Charset bodyCharset, String boundary) {
        this.bodyCharset = bodyCharset;
        this.boundary = boundary;
    }

    public void parse(InputStream inputStream) throws IOException {

        BoundariedInputStream outer = new BoundariedInputStream(inputStream, "\r\n--" + boundary + "--\r\n");

        BoundariedInputStream bis = new BoundariedInputStream(outer, "--" + boundary + "\r\n");

        byte[] buffer = new byte[8192];
        int read;

        while (bis.read(buffer) > -1) {
            // eat up the preamble
        }
        bis.changeBoundary("\r\n--" + boundary + "\r\n");


        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();


        while ((bis = bis.continueNext()) != null) {
            int offset = 0;
            partState = PartState.HEADERS;

            MediaType partType = MediaType.TEXT_PLAIN_TYPE;
            String formName = null;

            while ((read = bis.read(buffer)) > -1) {

                byte lastB = 0;

                int i = 0;
                if (partState == PartState.HEADERS) {
                    headerparser:
                    for (i = 0; i < read; i++) {
                        byte b = buffer[i];
                        if (lastB == '\r' && b == '\n') {
                            lineBuffer.write(buffer, offset, i - offset - 1);
                            String line = lineBuffer.toString(bodyCharset.name());
                            lineBuffer.reset();

                            if (line.isEmpty()) {
                                partState = PartState.BODY;
                                i++;
                                break headerparser;
                            } else {
                                String[] bits = line.split(":", 2);
                                String headerName = bits[0].trim().toLowerCase();
                                if (headerName.equals("content-disposition")) {
                                    HeaderValue disposition = HeaderValue.fromString(bits[1]).get(0);
                                    if ("form-data".equals(disposition.value())) {
                                        formName = disposition.parameters().get("name");
                                    } else {
                                        log.warn("Unsupported multipart-form part: " + disposition.value() + " - this part will be ignored");
                                    }
                                } else if (headerName.equals("content-type")) {
                                    partType = MediaTypeParser.fromString(bits[1]);
                                }
                            }

                            offset = i + 1;
                        }
                        lastB = b;
                    }
                }
                if (partState == PartState.BODY) {
                    bodyBuffer.write(buffer, i, read - i);
                }
            }

            if (formName != null) {
                String partCharset = partType.getParameters().getOrDefault("charset", "UTF-8");
                String formValue = bodyBuffer.toString(partCharset);
                formParams.putSingle(formName, formValue);
            }
            bodyBuffer.reset();
        }

        while (inputStream.read(buffer) > -1) {
            // consume any epilogue
        }
        inputStream.close();
    }


    List<String> formValue(String name) {
        return this.formParams.getOrDefault(name, emptyList());
    }

    MultivaluedMap<String, String> formParams() {
        return formParams;
    }
}
