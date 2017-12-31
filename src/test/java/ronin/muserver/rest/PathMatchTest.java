package ronin.muserver.rest;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class PathMatchTest {

    @Test
    public void canMatchPathNames() {
        PathMatch match = PathMatch.match("/fruit/{name}", "/fruit/orange");
        assertThat(match.matches(), is(true));
        assertThat(match.params().get("name"), equalTo("orange"));
    }

    @Test
    public void uriTemplatesCanBeConvertedToRegexes() {
        assertThat(pattern("/fruit"), equalTo("/\\Qfruit\\E(/.*)?"));
        assertThat(pattern("/fruit/{name}"), equalTo("/\\Qfruit\\E/(?<name>[ˆ/]+?)(/.*)?"));
        assertThat(pattern("/fruit/{version : v[12]}"), equalTo("/\\Qfruit\\E/(?<version>v[12])(/.*)?"));
        assertThat(pattern("/fruit/{version:v[12]}"), equalTo("/\\Qfruit\\E/(?<version>v[12])(/.*)?"));
        assertThat(pattern("/fruit/{version: v[12]}/{name}/eat"), equalTo("/\\Qfruit\\E/(?<version>v[12])/(?<name>[ˆ/]+?)/\\Qeat\\E(/.*)?"));
    }
    @Test(expected = IllegalArgumentException.class)
    public void throwsIfRegexPatternIsInvalid() {
        // TODO: make the error message easy to understand for app developers
        PathMatch.uriTemplateToRegex("/fruit/{version : v[12](?<blah}");
    }

    private static String pattern(String template) {
        return PathMatch.uriTemplateToRegex(template).pattern();
    }

}