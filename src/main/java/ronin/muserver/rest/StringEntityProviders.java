package ronin.muserver.rest;

import ronin.muserver.Mutils;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
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

import static java.util.Arrays.asList;
import static ronin.muserver.rest.EntityProviders.charsetFor;
import static ronin.muserver.rest.EntityProviders.requestHasContent;

class StringEntityProviders {

    static final List<MessageBodyReader> stringEntityReaders = asList(
        StringMessageReaderWriter.INSTANCE, new ReaderEntityReader(), CharArrayReaderWriter.INSTANCE
    );
    static final List<MessageBodyWriter> stringEntityWriters = asList(
        StringMessageReaderWriter.INSTANCE, CharArrayReaderWriter.INSTANCE
    );


    @Produces("*/*")
    @Consumes("*/*")
    private static class StringMessageReaderWriter implements MessageBodyWriter<String>, MessageBodyReader<String> {
        private StringMessageReaderWriter() {}
        public static final StringMessageReaderWriter INSTANCE = new StringMessageReaderWriter();

        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return String.class.equals(type);
        }

        public long getSize(String s, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return s.length();
        }

        public void writeTo(String s, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            entityStream.write(s.getBytes(charsetFor(mediaType)));
        }

        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return String.class.equals(type);
        }

        public String readFrom(Class<String> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
            if (!requestHasContent(httpHeaders)) {
                return "";
            }
            return new String(Mutils.toByteArray(entityStream, 2048), charsetFor(mediaType));
        }
    }
    @Produces("*/*")
    @Consumes("*/*")
    private static class CharArrayReaderWriter implements MessageBodyWriter<char[]>, MessageBodyReader<char[]> {
        private CharArrayReaderWriter() {}
        public static final CharArrayReaderWriter INSTANCE = new CharArrayReaderWriter();


        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type.isArray() && type.getComponentType().equals(char.class);
        }

        @Override
        public char[] readFrom(Class<char[]> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
            if (!requestHasContent(httpHeaders)) {
                return new char[0];
            }
            InputStreamReader reader = new InputStreamReader(entityStream, charsetFor(mediaType));
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
            return chars.length;
        }

        @Override
        public void writeTo(char[] chars, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            ByteBuffer bb = charsetFor(mediaType).encode(CharBuffer.wrap(chars));
            byte[] bytes = new byte[bb.remaining()];
            bb.get(bytes);
            entityStream.write(bytes);
            Arrays.fill(bb.array(), (byte)0); // if returning char[] arrays, it might be because it's a password etc, so blank it out
        }
    }

    @Consumes("*/*")
    private static class ReaderEntityReader implements MessageBodyReader<Reader> {
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return Reader.class.isAssignableFrom(type);
        }
        public Reader readFrom(Class<Reader> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
            return new InputStreamReader(entityStream, charsetFor(mediaType));
        }
    }
}
