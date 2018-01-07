package ronin.muserver.rest;

import ronin.muserver.Mutils;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

import static java.util.Arrays.asList;
import static ronin.muserver.rest.EntityProviders.requestHasContent;

class BinaryEntityProviders {

    static final List<MessageBodyReader> binaryEntityReaders = asList(
        new ByteArrayReaderWriter()
    );
    static final List<MessageBodyWriter> binaryEntityWriters = asList(
        new StreamingOutputWriter(),
        new ByteArrayReaderWriter()
    );

    @Produces("*/*")
    @Consumes("*/*")
    private static class ByteArrayReaderWriter implements MessageBodyReader<byte[]>, MessageBodyWriter<byte[]> {
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type.isArray() && type.getComponentType().equals(byte.class);
        }
        public byte[] readFrom(Class<byte[]> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
            if (!requestHasContent(httpHeaders)) {
                return new byte[0];
            }
            return Mutils.toByteArray(entityStream, 2048);
        }
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type.isArray() && type.getComponentType().equals(byte.class);
        }
        public long getSize(byte[] bytes, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return bytes.length;
        }
        public void writeTo(byte[] bytes, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            entityStream.write(bytes);
        }
    }


    @Produces("*/*")
    @Consumes("*/*")
    private static class StreamingOutputWriter implements MessageBodyWriter<StreamingOutput> {

        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return StreamingOutput.class.isAssignableFrom(type);
        }

        public void writeTo(StreamingOutput streamingOutput, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            streamingOutput.write(entityStream);
        }
    }

}
