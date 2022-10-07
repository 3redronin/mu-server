package io.muserver.rest;

import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.MessageBodyWriter;
import org.junit.Test;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.stream.Collectors;

import static io.muserver.rest.EntityProviders.builtInWriters;
import static io.muserver.rest.MediaTypeDeterminer.determine;
import static io.muserver.rest.ObjWithType.objType;
import static jakarta.ws.rs.core.MediaType.WILDCARD_TYPE;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class MediaTypeDeterminerTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    @Test
    public void ifTheResponseObjectIsAJAXResponseThatSpecifiesTheTypeThenUseThat() {
        Response response = Response.ok().type(MediaType.APPLICATION_XHTML_XML_TYPE).build();
        MediaType mediaType = determine(objType(response), emptyList(), emptyList(), wrapped(builtInWriters()), emptyList(), new Annotation[0]);
        assertThat(mediaType.toString(), equalTo("application/xhtml+xml"));
    }

    @Test
    public void ifTheResponseObjectIsNotAJAXResponseAndThereIsOneMethodProduceThenUseThat() {
        MediaType mediaType = determine(objType("Hello"), singletonList(MediaType.APPLICATION_ATOM_XML_TYPE), singletonList(MediaType.APPLICATION_SVG_XML_TYPE), wrapped(builtInWriters()), emptyList(), new Annotation[0]);
        assertThat(mediaType.toString(), equalTo("application/svg+xml"));
    }

    @Test
    public void ifTheResponseObjectIsNotAJAXResponseAndThereIsOneClassButNotMethodProduceThenUseThat() {
        MediaType mediaType = determine(objType("Hello"), singletonList(MediaType.APPLICATION_SVG_XML_TYPE), emptyList(), wrapped(builtInWriters()), emptyList(), new Annotation[0]);
        assertThat(mediaType.toString(), equalTo("application/svg+xml"));
    }


    @Test
    public void ifNoResponseTypeAndNoMethodProducesThenUseEntityProviders() {
        MediaType mediaType = determine(objType("Hello"), emptyList(), emptyList(), wrapped(builtInWriters()), singletonList(MediaType.valueOf("application/x-www-form-urlencoded")), new Annotation[0]);
        assertThat(mediaType.toString(), equalTo("application/x-www-form-urlencoded"));
    }

    @Test
    public void applicationOctectStreamIsTheDefault() {
        class SomethingNotAString {}
        MediaType mediaType = determine(objType(new SomethingNotAString()), emptyList(), emptyList(), wrapped(builtInWriters()), emptyList(), new Annotation[0]);
        assertThat(mediaType.toString(), equalTo("application/octet-stream"));
    }

    @Test(expected = NotAcceptableException.class)
    public void a406IsThrownIfNothingSuitable() {
        List<MessageBodyWriter> writers = singletonList(new StringEntityProviders.FormUrlEncodedWriter());
        List<MediaType> clientAccepts = singletonList(MediaType.valueOf("application/json"));
        determine(objType(new MultivaluedHashMap<>()), emptyList(), emptyList(), wrapped(writers), clientAccepts, new Annotation[0]);
    }

    @Test
    public void applicationOctectStreamIsUsedIfThereAreNoCompatibleWriters() {
        List<MessageBodyWriter> writers = singletonList(new StringEntityProviders.FormUrlEncodedWriter());
        MediaType mediaType = determine(objType("Hello"), emptyList(), emptyList(), wrapped(writers), emptyList(), new Annotation[0]);
        assertThat(mediaType.toString(), equalTo("application/octet-stream"));
    }

    @Test
    public void applicationOctetStreamIsDefaultEvenForStrings() {
        MediaType mediaType = determine(objType("Hello"), singletonList(WILDCARD_TYPE), emptyList(), wrapped(builtInWriters()), emptyList(), new Annotation[0]);
        assertThat(mediaType.toString(), equalTo("application/octet-stream"));
    }

    private static List<ProviderWrapper<MessageBodyWriter<?>>> wrapped(List<MessageBodyWriter> writers) {
        return writers.stream().map(ProviderWrapper::writer).collect(Collectors.toList());
    }

}
