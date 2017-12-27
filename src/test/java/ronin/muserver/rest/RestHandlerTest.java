package ronin.muserver.rest;

import org.junit.Test;
import ronin.muserver.Method;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

import java.net.URI;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class RestHandlerTest {

    @Test
    public void matchesBasedOnThePathAnnotation() {
        Fruit rest = new Fruit();

        RestHandler handler = new RestHandler(rest);
        assertThat(handler.matches(Method.GET, uri("/api/fruits")), is(true));
        assertThat(handler.matches(Method.POST, uri("/api/fruits")), is(false));
        assertThat(handler.matches(Method.GET, uri("/api/bats")), is(false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void throwsIfObjectDoesNotHavePathAnnotation() {
        new RestHandler(new Object());
    }

    private static URI uri(String path) {
        return URI.create("https://localhost:8443" + path);
    }

    @Path("/api/fruits")
    private static class Fruit {

        @GET
        public String getAll() {
            return "[ { \"name\": \"apple\" }, { \"name\": \"orange\" } ]";
        }
    }

}