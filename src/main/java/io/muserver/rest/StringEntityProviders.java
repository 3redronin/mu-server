package io.muserver.rest;

import io.muserver.Mutils;
import io.muserver.QueryString;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.muserver.Mutils.urlEncode;
import static java.util.Arrays.asList;

class StringEntityProviders {

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
            if (s == null || s.isEmpty()) {
                return 0;
            }
            if (s.length() > 100000) {
                return -1;
            }
            Charset charset = EntityProviders.charsetFor(mediaType);
            return getEncodedByteLength(s, charset);
        }

        static long getEncodedByteLength(String input, Charset charset) {
            if (input == null || input.isEmpty()) {
                return 0L;
            }
            // get the length without loading the whole thing into memory
            CharsetEncoder encoder = charset.newEncoder();
            CharBuffer charBuffer = CharBuffer.wrap(input);
            long byteLength = 0L;
            ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
            while (charBuffer.hasRemaining()) {
                encoder.encode(charBuffer, byteBuffer, false);
                byteLength += byteBuffer.position();
                byteBuffer.clear();
            }
            encoder.encode(charBuffer, byteBuffer, true);
            byteLength += byteBuffer.position();
            return byteLength;
        }

        public void writeTo(String s, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            entityStream.write(s.getBytes(EntityProviders.charsetFor(mediaType)));
        }

        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return String.class.equals(type);
        }

        public String readFrom(Class<String> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
            return new String(Mutils.toByteArray(entityStream, 2048), EntityProviders.charsetFor(mediaType));
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
            String s = new String(Mutils.toByteArray(entityStream, 512), EntityProviders.charsetFor(mediaType));
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
            return instant.toString().getBytes(EntityProviders.charsetFor(mediaType));
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
            String body = new String(Mutils.toByteArray(entityStream, 2048), EntityProviders.charsetFor(mediaType));
            QueryString formDecoder = QueryString.parse(body);
            Map<String, List<String>> parameters = formDecoder.all();
            MultivaluedHashMap<String, String> form = new MultivaluedHashMap<>();
            form.putAll(parameters);
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
            var sb = new StringBuilder();
            for (String key : form.keySet()) {
                String encodedKey = urlEncode(key);
                for (String value : form.get(key)) {
                    if (sb.length() > 1) sb.append('&');
                    sb.append(encodedKey).append('=').append(urlEncode(value));
                }
            }
            entityStream.write(sb.toString().getBytes(EntityProviders.charsetFor(mediaType)));
        }
    }
}
