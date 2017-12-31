package ronin.muserver.rest;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;

public class UriPatternTest {

    @Test
    public void uriTemplatesCanBeConvertedToRegexes() {
        assertThat(pattern("/fruit"), equalTo("/\\Qfruit\\E(/.*)?"));
        assertThat(pattern("/fruit/{name}"), equalTo("/\\Qfruit\\E/(?<name>[ˆ/]+?)(/.*)?"));
        assertThat(pattern("/fruit/{version : v[12]}"), equalTo("/\\Qfruit\\E/(?<version>v[12])(/.*)?"));
        assertThat(pattern("/fruit/{version:v[12]}"), equalTo("/\\Qfruit\\E/(?<version>v[12])(/.*)?"));
        assertThat(pattern("/fruit/{version: v[12]}/{name}/eat"), equalTo("/\\Qfruit\\E/(?<version>v[12])/(?<name>[ˆ/]+?)/\\Qeat\\E(/.*)?"));
    }

    @Test
    public void namedGroupsAreReturnedInThePattern() {
        UriPattern pattern = UriPattern.uriTemplateToRegex("/fruit/{version: v[12]}/{name}/eat");
        assertThat(pattern.namedGroups(), hasSize(2));
        assertThat(pattern.namedGroups(), hasItems("name", "version"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void throwsIfRegexPatternIsInvalid() {
        // TODO: make the error message easy to understand for app developers
        UriPattern.uriTemplateToRegex("/fruit/{version : v[12](?<blah}");
    }

    private static String pattern(String template) {
        return UriPattern.uriTemplateToRegex(template).pattern();
    }


}