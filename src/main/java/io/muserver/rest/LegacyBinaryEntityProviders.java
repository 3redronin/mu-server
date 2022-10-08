package io.muserver.rest;

import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;

import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

class LegacyBinaryEntityProviders {

    @Produces("*/*")
    static class StreamingOutputWriter implements MessageBodyWriter<javax.ws.rs.core.StreamingOutput> {
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return StreamingOutput.class.isAssignableFrom(type);
        }

        public void writeTo(javax.ws.rs.core.StreamingOutput streamingOutput, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            streamingOutput.write(entityStream);
        }
    }

}
