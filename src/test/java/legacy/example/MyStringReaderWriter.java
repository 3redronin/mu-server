package legacy.example;

import io.muserver.Mutils;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

@Produces({"text/plain", "*/*"})
@Consumes({"text/plain", "*/*"})
public class MyStringReaderWriter implements MessageBodyWriter<String>, MessageBodyReader<String> {

    // this is in a non-io.muserver package so that it gets classified as a customer reader/writer

    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return String.class.equals(type);
    }

    public long getSize(String s, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return s.length();
    }

    public void writeTo(String s, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
        entityStream.write(s.toUpperCase().getBytes("UTF-8"));
    }

    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return String.class.equals(type);
    }

    public String readFrom(Class<String> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
        return "--" + new String(Mutils.toByteArray(entityStream, 2048), "UTF-8") + "--";
    }
}
