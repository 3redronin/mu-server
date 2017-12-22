package scaffolding;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import static java.nio.charset.StandardCharsets.UTF_8;

public class FileUtils {

    public static String readResource(String classpathPath) throws IOException {
        InputStream stream = FileUtils.class.getResourceAsStream(classpathPath);
        if (stream == null) {
            throw new RuntimeException("Could not find " + classpathPath);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, UTF_8))) {
            int read;
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            while ((read = reader.read(buffer)) > -1) {
                sb.append(buffer, 0, read);
            }
            return sb.toString();
        } finally {
            stream.close();
        }
    }
}
