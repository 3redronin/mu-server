package ronin.muserver.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;

public interface ResourceProvider {
    boolean exists();

    Long fileSize();

    void writeTo(OutputStream out, int bufferSizeInBytes) throws IOException;
}

class FileProvider implements ResourceProvider {
    private final Path localPath;

    FileProvider(Path baseDirectory, String relativePath) {
        this.localPath = baseDirectory.resolve(relativePath);
    }

    public boolean exists() {
        return Files.exists(localPath);
    }

    public Long fileSize() {
        try {
            return Files.size(localPath);
        } catch (IOException e) {
            System.out.println("Error finding file size: " + e.getMessage());
            return null;
        }
    }

    public void writeTo(OutputStream out, int bufferSizeInBytes) throws IOException {
        long copy = Files.copy(localPath, out);
        System.out.println("Sent " + copy + " bytes for " + localPath);
    }

}

class ClasspathResourceProvider implements ResourceProvider {
    private final URLConnection info;

    public ClasspathResourceProvider(String classpathBase, String relativePath) {
        URLConnection con;
        if (relativePath.contains("..")) {
            con = null;
        } else {
            String path = classpathBase + "/" + relativePath;
            URL resource = ClasspathResourceProvider.class.getResource(path);
            if (resource == null || resource.getPath().endsWith("/")) {
                con = null;
            } else {
                try {
                    con = resource.openConnection();
                } catch (IOException e) {
                    System.out.println("Error " + e.getMessage());
                    con = null;
                }
            }
        }
        this.info = con;
    }

    public boolean exists() {
        return info != null;
    }

    public Long fileSize() {
        long size = info.getContentLengthLong();
        return size >= 0 ? size : null;
    }

    public void writeTo(OutputStream out, int bufferSizeInBytes) throws IOException {
        byte[] buffer = new byte[bufferSizeInBytes];
        int read;
        try (InputStream stream = info.getInputStream()) {
            while ((read = stream.read(buffer)) > -1) {
                out.write(buffer, 0, read);
            }
        }
    }

}