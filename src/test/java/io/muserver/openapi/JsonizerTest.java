package io.muserver.openapi;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class JsonizerTest {


    @Test
    public void itCanEscapeStuffProperly() throws IOException {

        StringWriter writer = new StringWriter();

        String key = "你好 umm \n \r \r\n yeah \\ / \\b \\f \\t \" 再见";
        String val = "value for " + key;

        writer.append('{');
        Jsonizer.append(writer, key, val, true);
        writer.append('}');

        assertThat(writer.toString(), equalTo("{\"你好 umm \\n \\r \\r\\n yeah \\\\ / \\\\b \\\\f \\\\t \\\" 再见\":\"value for 你好 umm \\n \\r \\r\\n yeah \\\\ / \\\\b \\\\f \\\\t \\\" 再见\"}"));

    }

}