package io.muserver.openapi;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.util.Collections;

import static io.muserver.openapi.XmlObjectBuilder.xmlObject;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class OpenAPIObjectTest {

    @Test
    public void canWriteToJSON() throws IOException {

        OpenAPIObject doc = new OpenAPIObject(
            new InfoObject("The Title", "The description", "Terms of service",
                new ContactObject("My name", URI.create("http://muserver.io"), "support@muserver.io"),
                new LicenseObject("Apache 2.0", URI.create("https://www.apache.org/licenses/LICENSE-2.0.html")),
                "1.0"
            ),
            asList(
                new ServerObjectBuilder().withUrl("http://muserver.io/api").withDescription("Production").withVariables(null).build(),
                new ServerObjectBuilder().withUrl("http://muserver.io/api{version}").withDescription("Production").withVariables(Collections.singletonMap("version", new ServerVariableObjectBuilder().withEnumValues(asList("1.0", "2.0")).withDefaultValue("2.0").withDescription("API Version").build())).build()
            ),
            new PathsObjectBuilder().withPathItemObjects(emptyMap()).build(), null, null, null, null);

        try (StringWriter writer = new StringWriter()) {
            doc.writeJson(writer);
            assertThat(writer.toString(), equalTo("{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"The Title\",\"description\":\"The description\",\"termsOfService\":\"Terms of service\",\"contact\":{\"name\":\"My name\",\"url\":\"http://muserver.io\",\"email\":\"support@muserver.io\"},\"license\":{\"name\":\"Apache 2.0\",\"url\":\"https://www.apache.org/licenses/LICENSE-2.0.html\"},\"version\":\"1.0\"},\"servers\":[{\"url\":\"http://muserver.io/api\",\"description\":\"Production\"},{\"url\":\"http://muserver.io/api{version}\",\"description\":\"Production\",\"variables\":{\"version\":{\"enum\":[\"1.0\",\"2.0\"],\"default\":\"2.0\",\"description\":\"API Version\"}}}],\"paths\":{}}"));
        }

        XmlObjectBuilder xml = xmlObject();

    }
}