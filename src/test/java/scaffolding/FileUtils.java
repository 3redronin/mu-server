package scaffolding;

import io.muserver.MuException;
import io.muserver.Mutils;

import java.io.*;

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

    public static File warAndPeaceInRussian() {
        File file = new File("src/test/resources/sample-static/war-and-peace-in-ISO-8859-5.txt");
        if (!file.isFile()) {
            throw new MuException("Could not find War and Peace in Russian at " + Mutils.fullPath(file));
        }
        return file;
    }

}
