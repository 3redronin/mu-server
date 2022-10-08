package io.muserver.rest;

import io.muserver.Mutils;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.QueryStringEncoder;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import javax.ws.rs.core.MultivaluedHashMap;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

class LegacyStringEntityProviders {

    @Consumes("application/x-www-form-urlencoded")
    static class FormUrlEncodedReader implements MessageBodyReader<javax.ws.rs.core.MultivaluedMap<String, String>> {

        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return javax.ws.rs.core.MultivaluedMap.class.isAssignableFrom(type);
        }

        @Override
        public javax.ws.rs.core.MultivaluedMap<String, String> readFrom(Class<javax.ws.rs.core.MultivaluedMap<String, String>> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
            String body = new String(Mutils.toByteArray(entityStream, 2048), EntityProviders.charsetFor(mediaType));
            QueryStringDecoder formDecoder = new QueryStringDecoder(body, false);
            Map<String, List<String>> parameters = formDecoder.parameters();
            javax.ws.rs.core.MultivaluedHashMap<String, String> form = new MultivaluedHashMap<>();
            for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
                form.put(entry.getKey(), entry.getValue());
            }
            return form;
        }
    }

    @Produces("application/x-www-form-urlencoded")
    static class FormUrlEncodedWriter implements MessageBodyWriter<javax.ws.rs.core.MultivaluedMap<String, String>> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return javax.ws.rs.core.MultivaluedMap.class.isAssignableFrom(type);
        }

        @Override
        public long getSize(javax.ws.rs.core.MultivaluedMap<String, String> stringStringMultivaluedMap, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return -1;
        }

        @Override
        public void writeTo(javax.ws.rs.core.MultivaluedMap<String, String> form, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            QueryStringEncoder encoder = new QueryStringEncoder("");
            for (Map.Entry<String, List<String>> entry : form.entrySet()) {
                for (String value : entry.getValue()) {
                    encoder.addParam(entry.getKey(), value);
                }
            }
            entityStream.write(encoder.toString().substring(1).getBytes(EntityProviders.charsetFor(mediaType)));
        }
    }
}
