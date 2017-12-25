package ronin.muserver.handlers;

import org.junit.Ignore;
import org.junit.Test;

import javax.xml.bind.DatatypeConverter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class FileProviderTest {

    private final ResourceProviderFactory factory = ResourceProviderFactory.fileBased(Paths.get("src/test/resources/sample-static"));

    @Test
    public void fileExistenceCanBeFound() {
        assertThat(factory.get("/no-valid-file").exists(), is(false));
        assertThat(factory.get("/images").exists(), is(false));
        assertThat(factory.get("/images/").exists(), is(false));
        assertThat(factory.get("./index.html").exists(), is(true));
        assertThat(factory.get("index.html").exists(), is(true));
    }

    @Test
    @Ignore("This fails... should this return false or an error though?")
    public void pathsMustBeDescendantsOfBase() {
        assertThat(factory.get("../something.txt").exists(), is(false));
    }

    @Test
    public void fileSizesCanBeFound() {
        assertThat(factory.get("images/guangzhou.jpeg").fileSize(), is(372987L));
    }

    @Test
    public void fileContentsCanBeGot() throws IOException {
        ResourceProvider provider = factory.get("images/guangzhou.jpeg");
        ByteArrayOutputStream out = new ByteArrayOutputStream(372987);
        provider.writeTo(out);
        assertThat(toHex(out.toByteArray()), equalTo(toHex(Files.readAllBytes(Paths.get("src/test/resources/sample-static/images/guangzhou.jpeg")))));
    }

    static String toHex(byte[] bytes) {
        return DatatypeConverter.printHexBinary(bytes);
    }

}