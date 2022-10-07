package io.muserver.rest;

import io.muserver.Mutils;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.QueryStringEncoder;

import javax.ws.rs.BadRequestException;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;

class LegacyStringEntityProviders {

    static final List<MessageBodyReader> stringEntityReaders = asList(
        StringMessageReaderWriter.INSTANCE, new FormUrlEncodedReader(), new ReaderEntityReader(),
        CharArrayReaderWriter.INSTANCE, TemporalEntityReaderWriter.INSTANCE
    );
    static final List<MessageBodyWriter> stringEntityWriters = asList(
        StringMessageReaderWriter.INSTANCE, CharArrayReaderWriter.INSTANCE, new FormUrlEncodedWriter(),
        TemporalEntityReaderWriter.INSTANCE
    );


    @Produces({"text/plain;charset=utf-8", "*/*"})
    @Consumes({"text/plain", "*/*"})
    static class StringMessageReaderWriter implements MessageBodyWriter<String>, MessageBodyReader<String> {
        private StringMessageReaderWriter() {
        }

        static final StringMessageReaderWriter INSTANCE = new StringMessageReaderWriter();

        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return String.class.equals(type);
        }

        public long getSize(String s, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            if (s.length() > 100000) {
                return -1;
            }

            Charset charset = LegacyEntityProviders.charsetFor(mediaType);
            if (charset.equals(StandardCharsets.UTF_8)) {
                return ByteBufUtil.utf8Bytes(s);
            }
            return s.getBytes(charset).length;
        }

        public void writeTo(String s, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            entityStream.write(s.getBytes(LegacyEntityProviders.charsetFor(mediaType)));
        }

        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return String.class.equals(type);
        }

        public String readFrom(Class<String> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
            return new String(Mutils.toByteArray(entityStream, 2048), LegacyEntityProviders.charsetFor(mediaType));
        }
    }

    @Produces({"text/plain;charset=utf-8", "*/*"})
    @Consumes({"text/plain", "*/*"})
    static class CharArrayReaderWriter implements MessageBodyWriter<char[]>, MessageBodyReader<char[]> {
        private CharArrayReaderWriter() {
        }

        static final CharArrayReaderWriter INSTANCE = new CharArrayReaderWriter();


        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type.isArray() && type.getComponentType().equals(char.class);
        }

        @Override
        public char[] readFrom(Class<char[]> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
            InputStreamReader reader = new InputStreamReader(entityStream, LegacyEntityProviders.charsetFor(mediaType));
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
            return new String(chars).getBytes(LegacyEntityProviders.charsetFor(mediaType)).length;
        }

        @Override
        public void writeTo(char[] chars, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            ByteBuffer bb = LegacyEntityProviders.charsetFor(mediaType).encode(CharBuffer.wrap(chars));
            byte[] bytes = new byte[bb.remaining()];
            bb.get(bytes);
            entityStream.write(bytes);
            Arrays.fill(bb.array(), (byte) 0); // if returning char[] arrays, it might be because it's a password etc, so blank it out
        }
    }

    @Produces({"text/plain;charset=utf-8"})
    @Consumes({"text/plain"})
    static class TemporalEntityReaderWriter<T extends Temporal> implements MessageBodyReader<T>, MessageBodyWriter<T> {
        static final TemporalEntityReaderWriter INSTANCE = new TemporalEntityReaderWriter();

        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return Temporal.class.isAssignableFrom(type);
        }

        @Override
        public T readFrom(Class<T> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
            String s = new String(Mutils.toByteArray(entityStream, 512), LegacyEntityProviders.charsetFor(mediaType));
            if (Mutils.nullOrEmpty(s)) {
                return null;
            }
            try {
                if (type.equals(Instant.class)) {
                    return (T) Instant.parse(s);
                } else if (type.equals(LocalDate.class)) {
                    return (T) LocalDate.parse(s);
                } else if (type.equals(LocalDateTime.class)) {
                    return (T) LocalDateTime.parse(s);
                } else if (type.equals(LocalTime.class)) {
                    return (T) LocalTime.parse(s);
                } else if (type.equals(ZonedDateTime.class)) {
                    return (T) ZonedDateTime.parse(s);
                } else if (type.equals(OffsetDateTime.class)) {
                    return (T) OffsetDateTime.parse(s);
                } else if (type.equals(OffsetTime.class)) {
                    return (T) OffsetTime.parse(s);
                } else if (type.equals(Year.class)) {
                    return (T) Year.parse(s);
                } else if (type.equals(YearMonth.class)) {
                    return (T) YearMonth.parse(s);
                }
                throw new BadRequestException("Unsupported temporal type " + type);
            } catch (DateTimeParseException e) {
                throw new BadRequestException("Invalid date format in request body", e);
            }
        }

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return Temporal.class.isAssignableFrom(type);
        }

        @Override
        public long getSize(T instant, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return getBytes(instant, mediaType).length;
        }

        @Override
        public void writeTo(T instant, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            entityStream.write(getBytes(instant, mediaType));
        }

        static byte[] getBytes(Temporal instant, MediaType mediaType) {
            if (instant == null) {
                return new byte[0];
            }
            return instant.toString().getBytes(LegacyEntityProviders.charsetFor(mediaType));
        }
    }

    @Consumes("*/*")
    static class ReaderEntityReader implements MessageBodyReader<Reader> {
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return Reader.class.isAssignableFrom(type);
        }

        public Reader readFrom(Class<Reader> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
            return new InputStreamReader(entityStream, LegacyEntityProviders.charsetFor(mediaType));
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
            String body = new String(Mutils.toByteArray(entityStream, 2048), LegacyEntityProviders.charsetFor(mediaType));
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
            entityStream.write(encoder.toString().substring(1).getBytes(LegacyEntityProviders.charsetFor(mediaType)));
        }
    }
}
