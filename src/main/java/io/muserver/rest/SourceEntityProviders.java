package io.muserver.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static java.util.Collections.singletonList;

class SourceEntityProviders {

    static final List<MessageBodyReader> sourceEntityReaders = singletonList(new SourceReader());
    static final List<MessageBodyWriter> sourceEntityWriters = singletonList(new SourceWriter());

    @Consumes({"text/xml", "application/xml", "application/*+xml"})
    static class SourceReader implements MessageBodyReader<Source> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return (type.equals(Source.class) || type.equals(StreamSource.class)) && isXml(mediaType);
        }

        @Override
        public Source readFrom(Class<Source> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                               MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            String charsetName = mediaType.getParameters().get(MediaType.CHARSET_PARAMETER);
            if (charsetName == null) {
                return new StreamSource(entityStream);
            }
            return new StreamSource(new BomAwareReader(entityStream, charsetName));
        }

        private static final class BomAwareReader extends Reader {
            private final PushbackInputStream input;
            private final String declaredCharset;
            private Reader delegate;

            private BomAwareReader(InputStream input, String declaredCharset) {
                this.input = new PushbackInputStream(input, 4);
                this.declaredCharset = declaredCharset;
            }

            @Override
            public int read(char[] chars, int offset, int length) throws IOException {
                if (offset < 0 || length < 0 || length > chars.length - offset) {
                    throw new IndexOutOfBoundsException();
                }
                if (length == 0) {
                    return 0;
                }
                return delegate().read(chars, offset, length);
            }

            @Override
            public void close() throws IOException {
                if (delegate == null) {
                    input.close();
                } else {
                    delegate.close();
                }
            }

            private Reader delegate() throws IOException {
                if (delegate == null) {
                    byte[] prefix = new byte[4];
                    int length = 0;
                    while (length < prefix.length) {
                        int read = input.read(prefix, length, prefix.length - length);
                        if (read <= 0) {
                            break;
                        }
                        length += read;
                    }

                    int bomLength = 0;
                    Charset charset;
                    if (startsWith(prefix, length, 0x00, 0x00, 0xfe, 0xff)) {
                        bomLength = 4;
                        charset = Charset.forName("UTF-32BE");
                    } else if (startsWith(prefix, length, 0xff, 0xfe, 0x00, 0x00)) {
                        bomLength = 4;
                        charset = Charset.forName("UTF-32LE");
                    } else if (startsWith(prefix, length, 0xef, 0xbb, 0xbf)) {
                        bomLength = 3;
                        charset = StandardCharsets.UTF_8;
                    } else if (startsWith(prefix, length, 0xfe, 0xff)) {
                        bomLength = 2;
                        charset = StandardCharsets.UTF_16BE;
                    } else if (startsWith(prefix, length, 0xff, 0xfe)) {
                        bomLength = 2;
                        charset = StandardCharsets.UTF_16LE;
                    } else {
                        try {
                            charset = Charset.forName(declaredCharset);
                        } catch (IllegalArgumentException e) {
                            throw new NotSupportedException("Unsupported XML charset " + declaredCharset, e);
                        }
                    }
                    if (length > bomLength) {
                        input.unread(prefix, bomLength, length - bomLength);
                    }
                    delegate = new InputStreamReader(input, charset);
                }
                return delegate;
            }

            private static boolean startsWith(byte[] actual, int actualLength, int... expected) {
                if (actualLength < expected.length) {
                    return false;
                }
                for (int i = 0; i < expected.length; i++) {
                    if ((actual[i] & 0xff) != expected[i]) {
                        return false;
                    }
                }
                return true;
            }
        }
    }

    @Produces({"application/xml", "text/xml;qs=0.9", "application/*+xml;qs=0.8"})
    static class SourceWriter implements MessageBodyWriter<Source> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return Source.class.isAssignableFrom(type) && (mediaType.isWildcardType() || isXml(mediaType));
        }

        @Override
        public void writeTo(Source source, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            try {
                Charset charset;
                try {
                    charset = EntityProviders.charsetFor(mediaType);
                } catch (IllegalArgumentException e) {
                    charset = StandardCharsets.UTF_8;
                    httpHeaders.putSingle("content-type", mediaType.withCharset("utf-8"));
                }
                Transformer transformer = newTransformer();
                transformer.setOutputProperty(OutputKeys.ENCODING, charset.name());
                transformer.transform(source, new StreamResult(entityStream));
            } catch (TransformerException e) {
                Throwable cause = e;
                while (cause != null) {
                    if (cause instanceof NotSupportedException) {
                        throw (NotSupportedException) cause;
                    }
                    Throwable next = cause.getCause();
                    if (next == cause) {
                        break;
                    }
                    cause = next;
                }
                throw new IOException("Could not write XML source", e);
            }
        }

        private static Transformer newTransformer() throws TransformerException {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            return factory.newTransformer();
        }
    }

    private static boolean isXml(MediaType mediaType) {
        String type = mediaType.getType();
        String subtype = mediaType.getSubtype();
        if ("text".equalsIgnoreCase(type) && "xml".equalsIgnoreCase(subtype)) {
            return true;
        }
        return "application".equalsIgnoreCase(type)
            && ("xml".equalsIgnoreCase(subtype)
            || (subtype.length() > 4 && subtype.toLowerCase(java.util.Locale.ROOT).endsWith("+xml")));
    }
}
