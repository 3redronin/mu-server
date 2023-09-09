package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static io.muserver.Mutils.coalesce;
import static java.util.Collections.emptyList;

class MultipartRequestBodyParser {
    private static final Logger log = LoggerFactory.getLogger(MultipartRequestBodyParser.class);
    private final Path fileUploadDir;

    private enum PartState {HEADERS, BODY}

    private final Charset bodyCharset;
    private final String boundary;
    private final MultivaluedMap<String, String> formParams = new MultivaluedHashMap<>();
    private final MultivaluedMap<String, UploadedFile> fileParams = new MultivaluedHashMap<>();


    MultipartRequestBodyParser(Path fileUploadDir, Charset bodyCharset, String boundary) {
        this.fileUploadDir = fileUploadDir;
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
        ByteArrayOutputStream bodyByteBuffer = new ByteArrayOutputStream();


        while ((bis = bis.continueNext()) != null) {
            int offset = 0;
            PartState partState = PartState.HEADERS;

            MediaType partType = MediaType.TEXT_PLAIN_TYPE;
            OutputStream fileOutputStream = null;
            Path file = null;
            String formName = null;
            String filename = null;

            while ((read = bis.read(buffer)) > -1) {

                byte lastB = 0;

                int i = 0;
                if (partState == PartState.HEADERS) {
                    headerparser:
                    for (i = 0; i < read; i++) {
                        byte b = buffer[i];
                        if (lastB == '\r' && b == '\n') {
                            lineBuffer.write(buffer, offset, i - offset - 1);
                            String line = lineBuffer.toString(bodyCharset);
                            lineBuffer.reset();

                            if (line.isEmpty()) {
                                partState = PartState.BODY;
                                i++;
                                break headerparser;
                            } else {
                                String[] bits = line.split(":", 2);
                                String headerName = bits[0].trim().toLowerCase();
                                if (headerName.equals("content-disposition")) {
                                    ParameterizedHeaderWithValue disposition = ParameterizedHeaderWithValue.fromString(bits[1]).get(0);
                                    if ("form-data".equals(disposition.value())) {
                                        formName = disposition.parameters().get("name");
                                        filename = disposition.parameters().get("filename");
                                        if (filename != null) {
                                            if (fileUploadDir != null) {
                                                Files.createDirectories(fileUploadDir);
                                                file = Files.createTempFile(fileUploadDir, "muserverupload", ".tmp");
                                            } else {
                                                file = Files.createTempFile("muserverupload", ".tmp");
                                            }
                                            fileOutputStream = Files.newOutputStream(file, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
                                        }
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
                    OutputStream os = coalesce(fileOutputStream, bodyByteBuffer);
                    os.write(buffer, i, read - i);
                }
            }

            if (formName != null) {
                String partCharset = partType.getParameters().getOrDefault("charset", "UTF-8");
                String formValue = filename != null ? filename : bodyByteBuffer.toString(partCharset);
                formParams.putSingle(formName, formValue);
                if (file != null) {
                    fileOutputStream.close();
                    MuUploadedFile2 muppet = new MuUploadedFile2(file, partType.getType() + "/" + partType.getSubtype(), filename);
                    fileParams.putSingle(formName, muppet);
                }
            }
            bodyByteBuffer.reset();
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

    MultivaluedMap<String, UploadedFile> fileParams() {
        return fileParams;
    }


    void clean() {
        for (List<UploadedFile> fileParam : this.fileParams.values()) {
            for (UploadedFile uploadedFile : fileParam) {
                ((MuUploadedFile2) uploadedFile).deleteFile();
            }
        }
    }
}
