package org.example;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

public class NumberWriter implements MessageBodyWriter<Number> {
    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return Number.class.isAssignableFrom(type);
    }

    @Override
    public void writeTo(Number number, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
        entityStream.write("custom-number".getBytes());
    }
}
