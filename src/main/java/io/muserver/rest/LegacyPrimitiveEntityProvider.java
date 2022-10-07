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

@Produces("text/plain;charset=utf-8")
@Consumes("text/plain")
class LegacyPrimitiveEntityProvider<T> implements MessageBodyWriter<T>, MessageBodyReader<T> {

    static final List<LegacyPrimitiveEntityProvider> primitiveEntryProviders = asList(
        new LegacyPrimitiveEntityProvider<>(int.class, Integer.class, Integer::parseInt),
        new LegacyPrimitiveEntityProvider<>(long.class, Long.class, Long::parseLong),
        new LegacyPrimitiveEntityProvider<>(short.class, Short.class, Short::parseShort),
        new LegacyPrimitiveEntityProvider<>(char.class, Character.class, s -> s.charAt(0)),
        new LegacyPrimitiveEntityProvider<>(byte.class, Byte.class, Byte::parseByte),
        new LegacyPrimitiveEntityProvider<>(float.class, Float.class, Float::parseFloat),
        new LegacyPrimitiveEntityProvider<>(double.class, Double.class, Double::parseDouble),
        new LegacyPrimitiveEntityProvider<>(boolean.class, Boolean.class, Boolean::parseBoolean)
    );

    private final Class primitiveClass;
    final Class<T> boxedClass;
    private final Function<String, T> stringToValue;

    private LegacyPrimitiveEntityProvider(Class primitiveClass, Class<T> boxedClass, Function<String, T> stringToValue) {
        this.primitiveClass = primitiveClass;
        this.boxedClass = boxedClass;
        this.stringToValue = stringToValue;
    }

    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return primitiveClass.equals(type) || boxedClass.equals(type);
    }

    public T readFrom(Class<T> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
        Charset charset = LegacyEntityProviders.charsetFor(mediaType);
        byte[] bytes = Mutils.toByteArray(entityStream, 2048);
        if (bytes.length == 0) {
            if (type.isPrimitive()) {
                throw new NoContentException("No value specified for this " + type.getName() + " parameter. If optional, then use a @DefaultValue annotation.");
            } else {
                return null;
            }
        }
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
        Charset charset = LegacyEntityProviders.charsetFor(mediaType);
        return String.valueOf(content).getBytes(charset);
    }
}
