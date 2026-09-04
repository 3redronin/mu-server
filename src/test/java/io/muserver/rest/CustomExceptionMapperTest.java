package io.muserver.rest;

import jakarta.ws.rs.core.Response;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.emptyList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class CustomExceptionMapperTest {


    private CustomExceptionMapper mapper;

    private static class ValidationException extends Exception {}
    private static class ConcurrentException extends ValidationException {}
    private static class SuperConcurrentException extends ConcurrentException {}
    private static class NoContentException extends Exception {}
    private static class ServerException extends Exception {}

    @Before
    public void setup() {
        MuRuntimeDelegate.ensureSet();
        List<JaxRSProviders.ExceptionMapperRegistration<?>> mappers = new ArrayList<>();
        mappers.add(new JaxRSProviders.ExceptionMapperRegistration<>(ValidationException.class,
            exception -> Response.status(400).build(), false));
        mappers.add(new JaxRSProviders.ExceptionMapperRegistration<>(ConcurrentException.class,
            exception -> Response.status(409).build(), false));
        mappers.add(new JaxRSProviders.ExceptionMapperRegistration<>(NoContentException.class,
            exception -> null, false));
        mappers.add(new JaxRSProviders.ExceptionMapperRegistration<>(ServerException.class,
            exception -> { throw new RuntimeException("oops");}, false));
        JaxRSProviders providers = new JaxRSProviders();
        providers.initialize(new EntityProviders(emptyList(), emptyList()), mappers, emptyList());
        mapper = new CustomExceptionMapper(providers);
    }

    @Test
    public void returnsNullIfThereIsNoAppropriateMapper() {
        Response response = mapper.toResponse(new IllegalStateException());
        assertThat(response, is(nullValue()));
    }

    @Test
    public void returnsExactMatches() {
        assertThat(mapper.toResponse(new ValidationException()).getStatus(), is(400));
        assertThat(mapper.toResponse(new ConcurrentException()).getStatus(), is(409));
    }

    @Test
    public void ifNoExactMatchesThenTheClosestOneIsUsed() {
        assertThat(mapper.toResponse(new SuperConcurrentException()).getStatus(), is(409));
    }

    @Test
    public void aMapperThatReturnsNullResultsInANoContentResponse() {
        Response response = mapper.toResponse(new NoContentException());
        assertThat(response.getStatus(), is(204));
    }
    @Test
    public void aMapperThatThrowsResultsInAnInternalServerError() {
        Response response = mapper.toResponse(new ServerException());
        assertThat(response.getStatus(), is(500));
    }

}
