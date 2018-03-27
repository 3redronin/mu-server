package io.muserver.openapi;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;

import static io.muserver.openapi.ContactObjectBuilder.contactObject;
import static io.muserver.openapi.InfoObjectBuilder.infoObject;
import static io.muserver.openapi.LicenseObjectBuilder.licenseObject;
import static io.muserver.openapi.OpenAPIObjectBuilder.openAPIObject;
import static io.muserver.openapi.PathsObjectBuilder.pathsObject;
import static io.muserver.openapi.ServerObjectBuilder.serverObject;
import static io.muserver.openapi.ServerVariableObjectBuilder.serverVariableObject;
import static io.muserver.openapi.TagObjectBuilder.tagObject;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class OpenAPIObjectTest {

    @Test
    public void canWriteToJSON() throws IOException {
        OpenAPIObject doc = openAPIObject()
            .withInfo(
                infoObject().withTitle("The Title").withDescription("The description").withTermsOfService(URI.create("http://example.org/terms"))
                    .withContact(contactObject().withName("My name").withUrl(URI.create("http://muserver.io")).withEmail("support@muserver.io").build())
                    .withLicense(licenseObject().withName("Apache 2.0").withUrl(URI.create("https://www.apache.org/licenses/LICENSE-2.0.html")).build())
                    .withVersion("1.0").build())
            .withServers(asList(
                serverObject().withUrl("http://muserver.io/api").withDescription("Production").build(),
                serverObject().withUrl("http://muserver.io/api{version}").withDescription("Production")
                    .withVariables(singletonMap("version", serverVariableObject().withEnumValues(asList("1.0", "2.0")).withDefaultValue("2.0").withDescription("API Version").build())).build()))
            .withPaths(pathsObject().withPathItemObjects(emptyMap()).build())
            .build();

        try (StringWriter writer = new StringWriter()) {
            doc.writeJson(writer);
            assertThat(writer.toString(), equalTo("{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"The Title\",\"description\":\"The description\",\"termsOfService\":\"http://example.org/terms\",\"contact\":{\"name\":\"My name\",\"url\":\"http://muserver.io\",\"email\":\"support@muserver.io\"},\"license\":{\"name\":\"Apache 2.0\",\"url\":\"https://www.apache.org/licenses/LICENSE-2.0.html\"},\"version\":\"1.0\"},\"servers\":[{\"url\":\"http://muserver.io/api\",\"description\":\"Production\"},{\"url\":\"http://muserver.io/api{version}\",\"description\":\"Production\",\"variables\":{\"version\":{\"enum\":[\"1.0\",\"2.0\"],\"default\":\"2.0\",\"description\":\"API Version\"}}}],\"paths\":{}}"));
        }
    }

    @Test
    public void multipleTagsAllowed() throws IOException {
        OpenAPIObject doc = openAPIObject()
            .withInfo(infoObject().withTitle("test").withVersion("1.0").build())
            .withPaths(pathsObject().withPathItemObjects(emptyMap()).build())
            .withTags(asList(
                tagObject().withName("something").build(),
                tagObject().withName("something else").build()
            ))
            .build();
        try (StringWriter writer = new StringWriter()) {
            doc.writeJson(writer);
            assertThat(writer.toString(), equalTo("{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"test\",\"version\":\"1.0\"},\"paths\":{},\"tags\":[{\"name\":\"something\"},{\"name\":\"something else\"}]}"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateTagsNotAllowed() {
        openAPIObject()
            .withInfo(infoObject().withTitle("test").withVersion("1.0").build())
            .withPaths(pathsObject().withPathItemObjects(emptyMap()).build())
            .withTags(asList(
                tagObject().withName("something").build(),
                tagObject().withName("something").build()
            ))
            .build();
    }

}