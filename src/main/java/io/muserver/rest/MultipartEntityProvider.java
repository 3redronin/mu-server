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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
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
        byte[] bytes = Mutils.toByteArray(entityStream, 8192);
        String body = new String(bytes, StandardCharsets.ISO_8859_1);
        String delimiter = "--" + boundary;
        int position = findBoundary(body, delimiter, 0);
        if (position < 0) {
            throw new BadRequestException("The multipart boundary was not found in the request body");
        }

        List<EntityPart> parts = new ArrayList<>();
        while (position >= 0) {
            int afterDelimiter = position + delimiter.length();
            if (body.startsWith("--", afterDelimiter)) {
                break;
            }
            if (!body.startsWith(CRLF, afterDelimiter)) {
                throw new BadRequestException("Invalid multipart boundary delimiter");
            }
            int headersStart = afterDelimiter + CRLF.length();
            int headersEnd = body.indexOf(CRLF + CRLF, headersStart);
            if (headersEnd < 0) {
                throw new BadRequestException("Invalid multipart part headers");
            }
            MultivaluedMap<String, String> partHeaders = parseHeaders(body.substring(headersStart, headersEnd));
            int contentStart = headersEnd + (CRLF + CRLF).length();
            int nextBoundary = findBoundary(body, CRLF + delimiter, contentStart);
            if (nextBoundary < 0) {
                throw new BadRequestException("The closing multipart boundary was not found");
            }

            String dispositionValue = partHeaders.getFirst(HttpHeaders.CONTENT_DISPOSITION);
            List<ParameterizedHeaderWithValue> dispositions = ParameterizedHeaderWithValue.fromString(dispositionValue);
            if (dispositions.isEmpty() || !"form-data".equalsIgnoreCase(dispositions.get(0).value())) {
                throw new BadRequestException("Each entity part must have a form-data Content-Disposition header");
            }
            String name = dispositions.get(0).parameter("name");
            if (name == null) {
                throw new BadRequestException("Each entity part must have a name");
            }
            String fileName = dispositions.get(0).parameter("filename");
            if (!partHeaders.containsKey(HttpHeaders.CONTENT_TYPE)) {
                partHeaders.putSingle(HttpHeaders.CONTENT_TYPE,
                    fileName == null ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_OCTET_STREAM);
            }
            byte[] content = body.substring(contentStart, nextBoundary).getBytes(StandardCharsets.ISO_8859_1);
            parts.add(new MuEntityPart(name, fileName, partHeaders, new ByteArrayInputStream(content), entityProviders));
            position = nextBoundary + CRLF.length();
        }
        return parts;
    }

    private static int findBoundary(String body, String marker, int fromIndex) {
        int candidate = body.indexOf(marker, fromIndex);
        while (candidate >= 0) {
            int afterMarker = candidate + marker.length();
            if (body.startsWith("--", afterMarker) || body.startsWith(CRLF, afterMarker)) {
                return candidate;
            }
            candidate = body.indexOf(marker, candidate + 1);
        }
        return -1;
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
}
