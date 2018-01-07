package ronin.muserver.rest;

import ronin.muserver.Mutils;

import javax.ws.rs.Consumes;
import javax.ws.rs.NotAcceptableException;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static ronin.muserver.rest.EntityProviders.charsetFor;
import static ronin.muserver.rest.EntityProviders.requestHasContent;

public class EntityProviders {

    private final List<MessageBodyReader> readers;
    private final List<MessageBodyWriter> writers;

    public EntityProviders(List<MessageBodyReader> readers, List<MessageBodyWriter> writers) {
        this.readers = readers;
        this.writers = writers;
    }
    public MessageBodyWriter<?> selectWriter(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        for (MessageBodyWriter<?> writer : writers) {
            if (writer.isWriteable(type, genericType, annotations, mediaType)) {
                return writer;
            }
        }
        throw new NotAcceptableException("Could not find a suitable entity provider to write " + type);
    }
    public MessageBodyReader<?> selectReader(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        for (MessageBodyReader<?> reader : readers) {
            if (reader.isReadable(type, genericType, annotations, mediaType)) {
                return reader;
            }
        }
        throw new NotAcceptableException("Could not find a suitable entity provider to read " + type);
    }

    public static List<MessageBodyReader> builtInReaders() {
        List<MessageBodyReader> readers = new ArrayList<>();
        readers.add(StringMessageReaderWriter.INSTANCE);
        readers.addAll(PrimitiveEntityProvider.primitiveEntryProviders);
        readers.addAll(BinaryEntityProviders.binaryEntityReaders);
        return readers;
    }
    public static List<MessageBodyWriter> builtInWriters() {
        List<MessageBodyWriter> writers = new ArrayList<>();
        writers.add(StringMessageReaderWriter.INSTANCE);
        writers.addAll(PrimitiveEntityProvider.primitiveEntryProviders);
        writers.addAll(BinaryEntityProviders.binaryEntityWriters);
        return writers;
    }


    static boolean requestHasContent(MultivaluedMap<String, String> headers) {
        String len = headers.getFirst("Content-Length");
        return headers.containsKey("Transfer-Encoding") || (len != null && Long.parseLong(len) > 0);
    }

    static Charset charsetFor(MediaType mediaType) {
        String charset = mediaType.getParameters().get("charset");
        if (charset == null) {
            return StandardCharsets.UTF_8;
        } else {
            return Charset.forName(charset);
        }
    }
}

@Produces("*/*")
@Consumes("*/*")
class StringMessageReaderWriter implements MessageBodyWriter<String>, MessageBodyReader<String> {
    private StringMessageReaderWriter() {}
    public static final StringMessageReaderWriter INSTANCE = new StringMessageReaderWriter();

    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return String.class.equals(type);
    }

    public long getSize(String s, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return s.length();
    }

    public void writeTo(String s, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
        entityStream.write(s.getBytes(charsetFor(mediaType)));
    }

    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return String.class.equals(type);
    }

    public String readFrom(Class<String> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
        if (!requestHasContent(httpHeaders)) {
            return "";
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Mutils.copy(entityStream, baos, 2048);
        Charset charset = charsetFor(mediaType);
        return new String(baos.toByteArray(), charset);
    }
}

