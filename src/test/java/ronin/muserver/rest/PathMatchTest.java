package ronin.muserver.rest;

import org.junit.Test;

import java.net.URI;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class PathMatchTest {

    @Test
    public void canMatchPathNames() {
        PathMatch match = PathMatch.match("/fruit/{name}", URI.create("/fruit/orange"));
        assertThat(match.matches(), is(true));
        assertThat(match.params().get("name"), equalTo("orange"));
    }


}