package io.muserver.rest;

import io.muserver.Mutils;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.QueryStringEncoder;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;

class StringEntityProviders {

    static final List<MessageBodyReader> stringEntityReaders = asList(
        StringMessageReaderWriter.INSTANCE, new FormUrlEncodedReader(), new ReaderEntityReader(), CharArrayReaderWriter.INSTANCE
    );
    static final List<MessageBodyWriter> stringEntityWriters = asList(
        StringMessageReaderWriter.INSTANCE, CharArrayReaderWriter.INSTANCE, new FormUrlEncodedWriter()
    );


    @Produces({"text/plain", "*/*"})
    @Consumes({"text/plain", "*/*"})
    static class StringMessageReaderWriter implements MessageBodyWriter<String>, MessageBodyReader<String> {
        private StringMessageReaderWriter() {}
        static final StringMessageReaderWriter INSTANCE = new StringMessageReaderWriter();

        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return String.class.equals(type);
        }

        public long getSize(String s, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            if (s.length() > 100000) {
                return -1;
            }
            return s.getBytes(EntityProviders.charsetFor(mediaType)).length;
        }

        public void writeTo(String s, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            entityStream.write(s.getBytes(EntityProviders.charsetFor(mediaType)));
        }

        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return String.class.equals(type);
        }

        public String readFrom(Class<String> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
            if (!EntityProviders.requestHasContent(httpHeaders)) {
                return "";
            }
            return new String(Mutils.toByteArray(entityStream, 2048), EntityProviders.charsetFor(mediaType));
        }
    }

    @Produces({"text/plain", "*/*"})
    @Consumes({"text/plain", "*/*"})
    static class CharArrayReaderWriter implements MessageBodyWriter<char[]>, MessageBodyReader<char[]> {
        private CharArrayReaderWriter() {}
        static final CharArrayReaderWriter INSTANCE = new CharArrayReaderWriter();


        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type.isArray() && type.getComponentType().equals(char.class);
        }

        @Override
        public char[] readFrom(Class<char[]> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
            if (!EntityProviders.requestHasContent(httpHeaders)) {
                return new char[0];
            }
            InputStreamReader reader = new InputStreamReader(entityStream, EntityProviders.charsetFor(mediaType));
            CharArrayWriter charArrayWriter = new CharArrayWriter();
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) > -1) {
                charArrayWriter.write(buffer, 0, read);
            }
            return charArrayWriter.toCharArray();
        }

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type.isArray() && type.getComponentType().equals(char.class);
        }

        @Override
        public long getSize(char[] chars, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            if (chars.length > 100000) {
                return -1;
            }
            return new String(chars).getBytes(EntityProviders.charsetFor(mediaType)).length;
        }

        @Override
        public void writeTo(char[] chars, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            ByteBuffer bb = EntityProviders.charsetFor(mediaType).encode(CharBuffer.wrap(chars));
            byte[] bytes = new byte[bb.remaining()];
            bb.get(bytes);
            entityStream.write(bytes);
            Arrays.fill(bb.array(), (byte)0); // if returning char[] arrays, it might be because it's a password etc, so blank it out
        }
    }

    @Consumes("*/*")
    static class ReaderEntityReader implements MessageBodyReader<Reader> {
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return Reader.class.isAssignableFrom(type);
        }
        public Reader readFrom(Class<Reader> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
            return new InputStreamReader(entityStream, EntityProviders.charsetFor(mediaType));
        }
    }

    @Consumes("application/x-www-form-urlencoded")
    static class FormUrlEncodedReader implements MessageBodyReader<MultivaluedMap<String, String>> {

        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return MultivaluedMap.class.isAssignableFrom(type);
        }

        @Override
        public MultivaluedMap<String, String> readFrom(Class<MultivaluedMap<String, String>> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
            if (!EntityProviders.requestHasContent(httpHeaders)) {
                return new MultivaluedHashMap<>();
            }
            String body = new String(Mutils.toByteArray(entityStream, 2048), EntityProviders.charsetFor(mediaType));
            QueryStringDecoder formDecoder = new QueryStringDecoder(body, false);
            Map<String, List<String>> parameters = formDecoder.parameters();
            MultivaluedHashMap<String, String> form = new MultivaluedHashMap<>();
            for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
                form.put(entry.getKey(), entry.getValue());
            }
            return form;
        }
    }

    @Produces("application/x-www-form-urlencoded")
    static class FormUrlEncodedWriter implements MessageBodyWriter<MultivaluedMap<String, String>> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return MultivaluedMap.class.isAssignableFrom(type);
        }

        @Override
        public long getSize(MultivaluedMap<String, String> stringStringMultivaluedMap, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return -1;
        }

        @Override
        public void writeTo(MultivaluedMap<String, String> form, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
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
