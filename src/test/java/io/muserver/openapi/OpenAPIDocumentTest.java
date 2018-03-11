package io.muserver.openapi;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.util.Collections;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class OpenAPIDocumentTest {

    @Test
    public void canWriteToJSON() throws IOException {

        OpenAPIDocument doc = new OpenAPIDocument(
            new Info("The Title", "The description", "Terms of service",
                new Contact("My name", URI.create("http://muserver.io"), "support@muserver.io"),
                new License("Apache 2.0", URI.create("https://www.apache.org/licenses/LICENSE-2.0.html")),
                "1.0"
            ),
            asList(
                new Server("http://muserver.io/api", "Production", null),
                new Server("http://muserver.io/api{version}", "Production", Collections.singletonMap("version", new ServerVariableObject(asList("1.0", "2.0"), "2.0", "API Version")))
            )
        );

        try (StringWriter writer = new StringWriter()) {
            doc.writeJson(writer);
            assertThat(writer.toString(), equalTo("{}"));
        }



    }
}