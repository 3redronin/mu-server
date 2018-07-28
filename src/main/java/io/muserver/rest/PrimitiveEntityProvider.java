package io.muserver.rest;

import io.muserver.Mutils;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NoContentException;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.List;
import java.util.function.Function;

import static java.util.Arrays.asList;

@Produces("text/plain; charset=UTF-8")
@Consumes("text/plain")
class PrimitiveEntityProvider<T> implements MessageBodyWriter<T>, MessageBodyReader<T> {

    static final List<PrimitiveEntityProvider> primitiveEntryProviders = asList(
        new PrimitiveEntityProvider<>(int.class, Integer.class, Integer::parseInt),
        new PrimitiveEntityProvider<>(long.class, Long.class, Long::parseLong),
        new PrimitiveEntityProvider<>(short.class, Short.class, Short::parseShort),
        new PrimitiveEntityProvider<>(char.class, Character.class, s -> s.charAt(0)),
        new PrimitiveEntityProvider<>(byte.class, Byte.class, Byte::parseByte),
        new PrimitiveEntityProvider<>(float.class, Float.class, Float::parseFloat),
        new PrimitiveEntityProvider<>(double.class, Double.class, Double::parseDouble),
        new PrimitiveEntityProvider<>(boolean.class, Boolean.class, Boolean::parseBoolean)
    );

    private final Class primitiveClass;
    final Class<T> boxedClass;
    private final Function<String, T> stringToValue;

    private PrimitiveEntityProvider(Class primitiveClass, Class<T> boxedClass, Function<String, T> stringToValue) {
        this.primitiveClass = primitiveClass;
        this.boxedClass = boxedClass;
        this.stringToValue = stringToValue;
    }

    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return primitiveClass.equals(type) || boxedClass.equals(type);
    }

    public T readFrom(Class<T> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
        if (!EntityProviders.requestHasContent(httpHeaders)) {
            throw new NoContentException("No value specified for this " + type.getName() + " parameter. If optional, then use a @DefaultValue annotation.");
        }
        Charset charset = EntityProviders.charsetFor(mediaType);
        byte[] bytes = Mutils.toByteArray(entityStream, 2048);
        String stringVal = new String(bytes, charset);
        return stringToValue.apply(stringVal);
    }

    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return primitiveClass.equals(type) || boxedClass.equals(type);
    }

    public long getSize(T content, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return toBytes(content, mediaType).length;
    }

    public void writeTo(T content, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
        entityStream.write(toBytes(content, mediaType));
    }

    private static byte[] toBytes(Object content, MediaType mediaType) {
        Charset charset = EntityProviders.charsetFor(mediaType);
        return String.valueOf(content).getBytes(charset);
    }
}
