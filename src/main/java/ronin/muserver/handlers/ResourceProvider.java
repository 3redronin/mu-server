package ronin.muserver.handlers;

import ronin.muserver.MuResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public interface ResourceProvider {
    boolean exists();
    Long fileSize();
    void writeTo(MuResponse response) throws IOException;
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

    public void writeTo(MuResponse response) throws IOException {
        try (OutputStream out = response.outputStream(16 * 1024)) {
            long copy = Files.copy(localPath, out);
            System.out.println("Sent " + copy + " bytes for " + localPath);
        }
    }
}