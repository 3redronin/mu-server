package org.example;

import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

@Produces({"text/plain;charset=utf-8", "*/*"})
public class RuntimeTypeOnlyStringWriter implements MessageBodyWriter<String> {
    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return type.equals(String.class) && genericType.equals(String.class);
    }

    @Override
    public void writeTo(String value, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
        entityStream.write("runtime-type".getBytes(StandardCharsets.UTF_8));
    }
}
