package ronin.muserver.rest;

import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

import static java.net.URI.create;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class ResourceClassTest {

    @Test
    public void canCreate() {
        ResourceClass rc = ResourceClass.fromObject(new Fruit());
        assertThat(rc.matches(create("/api/fruit")), equalTo(false));
        assertThat(rc.matches(create("/api/fruits")), equalTo(true));
        assertThat(rc.matches(create("/api/fruits?yeah=yeah")), equalTo(true));
        assertThat(rc.matches(create("/api/fruits/orange")), equalTo(true));
    }

    @Test
    public void pathParamsCanBeInheritedIfThereAreNoJaxAnnotations() {
        ResourceClass rc = ResourceClass.fromObject(new ConcreteWidget());
        assertThat(rc.matches(create("/api/widgets")), equalTo(true));
    }

    @Path("/api/fruits")
    private static class Fruit {

        @GET
        public String getAll() {
            return "[ { \"name\": \"apple\" }, { \"name\": \"orange\" } ]";
        }

        @Path("/:name")
        @GET
        public String get(@PathParam("name") String name) {
            switch (name) {
                case "apple":
                    return "{ \"name\": \"apple\" }";
                case "orange":
                    return "{ \"name\": \"orange\" }";
            }
            return "not found";
        }

        public void notEligible() {
        }

    }

    @Path("/api/widgets")
    private static abstract class BaseWidgetResource {

    }
    private static class ConcreteWidget extends BaseWidgetResource {

    }

}