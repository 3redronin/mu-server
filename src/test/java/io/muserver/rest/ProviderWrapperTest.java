package io.muserver.rest;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import org.junit.Test;

import javax.ws.rs.Consumes;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

public class ProviderWrapperTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    @Test
    public void itCanFigureOutGenericReaderTypes() {
        assertThat(readerType(StringEntityProviders.StringMessageReaderWriter.INSTANCE), equalTo(String.class));
        assertThat(readerType(StringEntityProviders.CharArrayReaderWriter.INSTANCE), equalTo(char[].class));
        assertThat(readerType(new StringEntityProviders.ReaderEntityReader()), equalTo(Reader.class));
        assertThat(readerType(new StringEntityProviders.FormUrlEncodedReader()).getTypeName(), equalTo("jakarta.ws.rs.core.MultivaluedMap<java.lang.String, java.lang.String>"));
        assertThat(readerType(new BinaryEntityProviders.ByteArrayReaderWriter()), equalTo(byte[].class));
        assertThat(readerType(new BinaryEntityProviders.InputStreamReader()), equalTo(InputStream.class));
        assertThat(readerType(new BinaryEntityProviders.FileReaderWriter()), equalTo(File.class));
    }

    @Test
    public void itCanFigureOutGenericWriterTypes() {
        assertThat(writerType(StringEntityProviders.StringMessageReaderWriter.INSTANCE), equalTo(String.class));
        assertThat(writerType(StringEntityProviders.CharArrayReaderWriter.INSTANCE), equalTo(char[].class));
        assertThat(writerType(new StringEntityProviders.FormUrlEncodedWriter()).getTypeName(), equalTo("jakarta.ws.rs.core.MultivaluedMap<java.lang.String, java.lang.String>"));
        assertThat(writerType(new BinaryEntityProviders.ByteArrayReaderWriter()), equalTo(byte[].class));
        assertThat(writerType(new BinaryEntityProviders.StreamingOutputWriter()), equalTo(StreamingOutput.class));
        assertThat(writerType(new BinaryEntityProviders.FileReaderWriter()), equalTo(File.class));
    }

    @Test
    public void itCanFindBoxedTypes() {
        for (PrimitiveEntityProvider provider : PrimitiveEntityProvider.primitiveEntryProviders) {
            assertThat(readerType(provider), equalTo(provider.boxedClass));
            assertThat(writerType(provider), equalTo(provider.boxedClass));
        }
    }

    @Test
    public void forLegacyJaxReadersItUsesTheTypeOnTheOldProvider() {
        class Dog { }
        @Consumes("text/dog")
        class DogBodyReader implements javax.ws.rs.ext.MessageBodyReader<Dog> {
            @Override
            public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType) {
                return Dog.class.isAssignableFrom(type);
            }
            @Override
            public Dog readFrom(Class<Dog> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType, javax.ws.rs.core.MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, javax.ws.rs.WebApplicationException {
                return new Dog();
            }
        }

        MessageBodyReader adapted = new RestHandlerBuilder.LegacyJaxRSMessageBodyReader(new DogBodyReader());
        ProviderWrapper<MessageBodyReader<?>> providerWrapper = ProviderWrapper.reader(adapted);
        assertThat(readerType(adapted), equalTo(Dog.class));
        assertThat("Actual: " + providerWrapper.mediaTypes, providerWrapper.mediaTypes, contains(MediaType.valueOf("text/dog")));
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
