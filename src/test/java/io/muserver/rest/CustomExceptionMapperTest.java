package io.muserver.rest;

import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import java.util.HashMap;
import java.util.Map;

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
        Map<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> mappers = new HashMap<>();
        mappers.put(ValidationException.class, exception -> Response.status(400).build());
        mappers.put(ConcurrentException.class, exception -> Response.status(409).build());
        mappers.put(NoContentException.class, exception -> null);
        mappers.put(ServerException.class, exception -> { throw new RuntimeException("oops");});
        mapper = new CustomExceptionMapper(mappers);
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