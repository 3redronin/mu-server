package io.muserver.rest;

import org.junit.Test;

import java.net.URI;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class PathMatchTest {

    @Test
    public void canMatchPathNames() {
        PathMatch match = UriPattern.uriTemplateToRegex("/fruit/{name}").matcher(URI.create("fruit/orange"));
        assertThat(match.prefixMatches(), is(true));
        assertThat(match.params().get("name"), equalTo("orange"));
    }


    @Test
    public void theEmptyMatcherMatches() {
        PathMatch emptyMatch = PathMatch.EMPTY_MATCH;
        assertThat(emptyMatch.prefixMatches(), is(true));
        assertThat(emptyMatch.params().isEmpty(), is(true));
        assertThat(emptyMatch.regexMatcher().matches(), is(true));
    }


}