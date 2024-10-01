package io.muserver.rest;

import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class LinkHeaderDelegateTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    @Test
    public void canParseSimpleLinks() {
        LinkHeaderDelegate lhd = new LinkHeaderDelegate();
        Link link = lhd.fromString("</>");
        assertThat(link.toString(), is("</>"));
    }

    @Test
    public void linkBuilderGala() {
        Link link = MuRuntimeDelegate.getInstance().createLinkBuilder()
            .baseUri("http://example.org/")
            .param("arb", "itrary value")
            .rel("href")
            .title("The title of \"my\" link")
            .type("blah")
            .uri("/something/{name}.html")
            .build("else");
        assertThat(link.getParams().get("arb"), is("itrary value"));
        assertThat(link.getRel(), is("href"));
        assertThat(link.getTitle(), is("The title of \"my\" link"));
        assertThat(link.getType(), is("blah"));
        assertThat(link.getUri().toString(), is("http://example.org/something/else.html"));

        assertThat(link.getUriBuilder().build().toString(), is("http://example.org/something/else.html"));
        assertThat(link.toString(), is("<http://example.org/something/else.html>; rel=\"href\"; title=\"The title of \\\"my\\\" link\"; type=\"blah\"; arb=\"itrary value\""));

        RuntimeDelegate.HeaderDelegate<Link> headerDelegate = MuRuntimeDelegate.getInstance().createHeaderDelegate(Link.class);
        Link reconstructed = headerDelegate.fromString(link.toString());

        assertThat(reconstructed, equalTo(link));
    }
}