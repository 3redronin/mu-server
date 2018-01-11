package io.muserver.rest;

import org.junit.Test;

import javax.activation.DataSource;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class ProviderWrapperTest {

    @Test
    public void itCanFigureOutGenericReaderTypes() {
        assertThat(readerType(StringEntityProviders.StringMessageReaderWriter.INSTANCE), equalTo(String.class));
        assertThat(readerType(StringEntityProviders.CharArrayReaderWriter.INSTANCE), equalTo(char[].class));
        assertThat(readerType(new StringEntityProviders.ReaderEntityReader()), equalTo(Reader.class));
        assertThat(readerType(new StringEntityProviders.FormUrlEncodedReader()).getTypeName(), equalTo("javax.ws.rs.core.MultivaluedMap<java.lang.String, java.lang.String>"));
        assertThat(readerType(new BinaryEntityProviders.ByteArrayReaderWriter()), equalTo(byte[].class));
        assertThat(readerType(new BinaryEntityProviders.InputStreamReader()), equalTo(InputStream.class));
        assertThat(readerType(new BinaryEntityProviders.FileReaderWriter()), equalTo(File.class));
        assertThat(readerType(new BinaryEntityProviders.DataSourceReaderWriter()), equalTo(DataSource.class));
    }

    @Test
    public void itCanFigureOutGenericWriterTypes() {
        assertThat(writerType(StringEntityProviders.StringMessageReaderWriter.INSTANCE), equalTo(String.class));
        assertThat(writerType(StringEntityProviders.CharArrayReaderWriter.INSTANCE), equalTo(char[].class));
        assertThat(writerType(new StringEntityProviders.FormUrlEncodedWriter()).getTypeName(), equalTo("javax.ws.rs.core.MultivaluedMap<java.lang.String, java.lang.String>"));
        assertThat(writerType(new BinaryEntityProviders.ByteArrayReaderWriter()), equalTo(byte[].class));
        assertThat(writerType(new BinaryEntityProviders.StreamingOutputWriter()), equalTo(StreamingOutput.class));
        assertThat(writerType(new BinaryEntityProviders.FileReaderWriter()), equalTo(File.class));
        assertThat(writerType(new BinaryEntityProviders.DataSourceReaderWriter()), equalTo(DataSource.class));
    }

    @Test
    public void itCanFindBoxedTypes() {
        for (PrimitiveEntityProvider provider : PrimitiveEntityProvider.primitiveEntryProviders) {
            assertThat(readerType(provider), equalTo(provider.boxedClass));
            assertThat(writerType(provider), equalTo(provider.boxedClass));
        }
    }

    private static Type readerType(Object instance) {
        return ProviderWrapper.genericTypeOf(instance, MessageBodyReader.class);
    }

    private static Type writerType(Object instance) {
        return ProviderWrapper.genericTypeOf(instance, MessageBodyWriter.class);
    }

    @Test
    public void itCanFindTheWrapperThatIsClosestToTheType() {
        ProviderWrapper<MessageBodyWriter<?>> stringWriter = createStringWriter();
        ProviderWrapper<MessageBodyWriter<?>> charSequenceWriter = createCharSequenceWriter();
        ProviderWrapper<MessageBodyWriter<?>> objectWriter = createObjectWriter();
        assertThat(ProviderWrapper.compareTo(stringWriter, objectWriter, String.class), equalTo(-1));
        assertThat(ProviderWrapper.compareTo(stringWriter, objectWriter, Object.class), equalTo(1));
        assertThat(ProviderWrapper.compareTo(stringWriter, stringWriter, String.class), equalTo(0));
        assertThat(ProviderWrapper.compareTo(objectWriter, objectWriter, Object.class), equalTo(0));
        assertThat(ProviderWrapper.compareTo(objectWriter, stringWriter, String.class), equalTo(1));
        assertThat(ProviderWrapper.compareTo(objectWriter, stringWriter, Object.class), equalTo(-1));


        assertThat(ProviderWrapper.compareTo(charSequenceWriter, stringWriter, String.class), equalTo(1));
        assertThat(ProviderWrapper.compareTo(stringWriter, charSequenceWriter, String.class), equalTo(-1));
        assertThat(ProviderWrapper.compareTo(charSequenceWriter, stringWriter, CharSequence.class), equalTo(-1));
        assertThat(ProviderWrapper.compareTo(stringWriter, charSequenceWriter, CharSequence.class), equalTo(1));

        assertThat(ProviderWrapper.compareTo(charSequenceWriter, stringWriter, Object.class), equalTo(-1));
        assertThat(ProviderWrapper.compareTo(stringWriter, charSequenceWriter, Object.class), equalTo(1));

    }

    private ProviderWrapper<MessageBodyWriter<?>> createStringWriter() {
        return ProviderWrapper.writer(new MessageBodyWriter<String>() {
            public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
                return true;
            }
            public void writeTo(String s, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            }
        });
    }
    private ProviderWrapper<MessageBodyWriter<?>> createObjectWriter() {
        return ProviderWrapper.writer(new MessageBodyWriter<Object>() {
            public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
                return true;
            }
            public void writeTo(Object o, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            }
        });
    }
    private ProviderWrapper<MessageBodyWriter<?>> createCharSequenceWriter() {
        return ProviderWrapper.writer(new MessageBodyWriter<CharSequence>() {
            public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
                return true;
            }
            public void writeTo(CharSequence charSequence, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
            }
        });
    }


}