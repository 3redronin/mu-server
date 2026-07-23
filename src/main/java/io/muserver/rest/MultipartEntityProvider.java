package io.muserver.rest;

import io.muserver.Mutils;
import io.muserver.ParameterizedHeaderWithValue;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.ref.Cleaner;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Consumes("multipart/*")
@Produces("multipart/*")
final class MultipartEntityProvider implements MessageBodyReader<List<EntityPart>>, MessageBodyWriter<Collection<EntityPart>> {

    private static final String CRLF = "\r\n";
    private final EntityProviders entityProviders;

    MultipartEntityProvider(EntityProviders entityProviders) {
        this.entityProviders = entityProviders;
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return (List.class.equals(type) || Collection.class.equals(type)) && containsEntityParts(genericType);
    }

    @Override
    public List<EntityPart> readFrom(Class<List<EntityPart>> type, Type genericType, Annotation[] annotations,
                                     MediaType mediaType, MultivaluedMap<String, String> httpHeaders,
                                     InputStream entityStream) throws IOException, WebApplicationException {
        String boundary = mediaType.getParameters().get("boundary");
        if (boundary == null || boundary.isEmpty()) {
            throw new BadRequestException("A multipart request must include a boundary parameter");
        }
        InputStream input = new BufferedInputStream(entityStream, 8192);
        String delimiter = "--" + boundary;
        String firstBoundary;
        do {
            firstBoundary = readLine(input);
        } while (firstBoundary != null
            && !delimiter.equals(firstBoundary)
            && !(delimiter + "--").equals(firstBoundary));
        if (firstBoundary == null) {
            throw new BadRequestException("The multipart boundary was not found in the request body");
        }
        if ((delimiter + "--").equals(firstBoundary)) {
            return new ArrayList<>();
        }

        List<EntityPart> parts = new ArrayList<>();
        List<DeletingFileInputStream> openedStreams = new ArrayList<>();
        try {
            boolean finished = false;
            while (!finished) {
                MultivaluedMap<String, String> partHeaders = readHeaders(input);
                File spoolFile = File.createTempFile("mu-entity-part-", ".tmp");
                spoolFile.deleteOnExit();
                BoundaryResult boundaryResult;
                try (OutputStream spool = new FileOutputStream(spoolFile)) {
                    boundaryResult = copyUntilBoundary(input,
                        (CRLF + delimiter).getBytes(StandardCharsets.ISO_8859_1), spool);
                } catch (IOException | RuntimeException | Error failure) {
                    spoolFile.delete();
                    throw failure;
                }

                String dispositionValue = partHeaders.getFirst(HttpHeaders.CONTENT_DISPOSITION);
                List<ParameterizedHeaderWithValue> dispositions =
                    ParameterizedHeaderWithValue.fromString(dispositionValue);
                if (dispositions.isEmpty() || !"form-data".equalsIgnoreCase(dispositions.get(0).value())) {
                    spoolFile.delete();
                    throw new BadRequestException(
                        "Each entity part must have a form-data Content-Disposition header");
                }
                String name = dispositions.get(0).parameter("name");
                if (name == null) {
                    spoolFile.delete();
                    throw new BadRequestException("Each entity part must have a name");
                }
                String fileName = dispositions.get(0).parameter("filename");
                if (!partHeaders.containsKey(HttpHeaders.CONTENT_TYPE)) {
                    partHeaders.putSingle(HttpHeaders.CONTENT_TYPE,
                        fileName == null ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_OCTET_STREAM);
                }
                DeletingFileInputStream content = new DeletingFileInputStream(spoolFile);
                openedStreams.add(content);
                parts.add(new MuEntityPart(name, fileName, partHeaders, content, entityProviders));
                finished = boundaryResult == BoundaryResult.CLOSING;
            }
            return parts;
        } catch (IOException | RuntimeException | Error failure) {
            for (DeletingFileInputStream stream : openedStreams) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
            throw failure;
        }
    }

    private static MultivaluedMap<String, String> readHeaders(InputStream input) throws IOException {
        StringBuilder block = new StringBuilder();
        while (true) {
            String line = readLine(input);
            if (line == null) {
                throw new BadRequestException("Invalid multipart part headers");
            }
            if (line.isEmpty()) {
                return parseHeaders(block.toString());
            }
            if (block.length() > 0) {
                block.append(CRLF);
            }
            block.append(line);
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) >= 0) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(current);
            previous = current;
            if (line.size() > 64 * 1024) {
                throw new BadRequestException("Multipart header line is too long");
            }
        }
        return line.size() == 0 ? null : new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static BoundaryResult copyUntilBoundary(InputStream input, byte[] marker, OutputStream output)
        throws IOException {
        int matched = 0;
        int current;
        while ((current = input.read()) >= 0) {
            if (current == (marker[matched] & 0xff)) {
                matched++;
                if (matched == marker.length) {
                    int suffixOne = input.read();
                    int suffixTwo = input.read();
                    if (suffixOne == '-' && suffixTwo == '-') {
                        return BoundaryResult.CLOSING;
                    }
                    if (suffixOne == '\r' && suffixTwo == '\n') {
                        return BoundaryResult.NEXT;
                    }
                    output.write(marker);
                    if (suffixOne >= 0) {
                        output.write(suffixOne);
                    }
                    if (suffixTwo >= 0) {
                        output.write(suffixTwo);
                    }
                    matched = 0;
                }
            } else {
                if (matched > 0) {
                    output.write(marker, 0, matched);
                    matched = 0;
                    if (current == (marker[0] & 0xff)) {
                        matched = 1;
                    } else {
                        output.write(current);
                    }
                } else {
                    output.write(current);
                }
            }
        }
        throw new BadRequestException("The closing multipart boundary was not found");
    }

    private static MultivaluedMap<String, String> parseHeaders(String headerBlock) {
        MultivaluedMap<String, String> headers = new LowercasedMultivaluedHashMap<>();
        if (headerBlock.isEmpty()) {
            return headers;
        }
        for (String line : headerBlock.split(CRLF)) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new BadRequestException("Invalid multipart header: " + line);
            }
            headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        return headers;
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return Collection.class.isAssignableFrom(type) && containsEntityParts(genericType);
    }

    @Override
    public void writeTo(Collection<EntityPart> parts, Class<?> type, Type genericType, Annotation[] annotations,
                        MediaType mediaType, MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        String boundary = "mu-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, String> parameters = new LinkedHashMap<>(mediaType.getParameters());
        parameters.put("boundary", boundary);
        MediaType multipartType = new MediaType(mediaType.getType(), mediaType.getSubtype(), parameters);
        httpHeaders.putSingle(HttpHeaders.CONTENT_TYPE, multipartType.toString());

        for (EntityPart part : parts) {
            writeAscii(entityStream, "--" + boundary + CRLF);
            for (Map.Entry<String, List<String>> header : part.getHeaders().entrySet()) {
                validateHeader(header.getKey());
                for (String value : header.getValue()) {
                    validateHeader(value);
                    writeAscii(entityStream, displayHeaderName(header.getKey()) + ": " + value + CRLF);
                }
            }
            writeAscii(entityStream, CRLF);
            try (InputStream content = part.getContent()) {
                Mutils.copy(content, entityStream, 8192);
            }
            writeAscii(entityStream, CRLF);
        }
        writeAscii(entityStream, "--" + boundary + "--" + CRLF);
    }

    private static void writeAscii(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void validateHeader(String value) {
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid multipart header value");
        }
    }

    private static String displayHeaderName(String name) {
        if (HttpHeaders.CONTENT_DISPOSITION.equalsIgnoreCase(name)) {
            return HttpHeaders.CONTENT_DISPOSITION;
        }
        if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
            return HttpHeaders.CONTENT_TYPE;
        }
        return name;
    }

    static String contentDisposition(String name, String fileName) {
        StringBuilder result = new StringBuilder("form-data; name=\"").append(quote(name)).append('"');
        if (fileName != null) {
            result.append("; filename=\"").append(quote(fileName)).append('"');
        }
        return result.toString();
    }

    private static String quote(String value) {
        validateHeader(value);
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean containsEntityParts(Type genericType) {
        if (!(genericType instanceof ParameterizedType)) {
            return false;
        }
        Type[] arguments = ((ParameterizedType) genericType).getActualTypeArguments();
        return arguments.length == 1 && arguments[0] == EntityPart.class;
    }

    private enum BoundaryResult {
        NEXT,
        CLOSING
    }

    private static final class DeletingFileInputStream extends FileInputStream {
        private static final Cleaner CLEANER = Cleaner.create();
        private final Cleaner.Cleanable cleanable;

        private DeletingFileInputStream(File file) throws IOException {
            super(file);
            this.cleanable = CLEANER.register(this, file::delete);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                cleanable.clean();
            }
        }
    }
}
