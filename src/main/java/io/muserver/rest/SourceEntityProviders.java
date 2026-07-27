package io.muserver.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import javax.xml.XMLConstants;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

import static java.util.Collections.singletonList;

class SourceEntityProviders {

    static final List<MessageBodyReader> sourceEntityReaders = singletonList(new SourceReader());
    static final List<MessageBodyWriter> sourceEntityWriters = singletonList(new SourceWriter());

    @Consumes("*/*")
    static class SourceReader implements MessageBodyReader<Source> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return Source.class.isAssignableFrom(type) && isXml(mediaType);
        }

        @Override
        public Source readFrom(Class<Source> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                               MultivaluedMap<String, String> httpHeaders, InputStream entityStream) {
            return new StreamSource(entityStream);
        }
    }

    @Produces("*/*")
    static class SourceWriter implements MessageBodyWriter<Source> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return Source.class.isAssignableFrom(type) && isXml(mediaType);
        }

        @Override
        public void writeTo(Source source, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            try {
                newTransformer().transform(source, new StreamResult(entityStream));
            } catch (TransformerException e) {
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
        if ("text".equalsIgnoreCase(mediaType.getType()) && "xml".equalsIgnoreCase(mediaType.getSubtype())) {
            return true;
        }
        return "application".equalsIgnoreCase(mediaType.getType())
            && ("xml".equalsIgnoreCase(mediaType.getSubtype())
            || mediaType.getSubtype().toLowerCase(java.util.Locale.ROOT).endsWith("+xml"));
    }
}
