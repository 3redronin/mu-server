package io.muserver.handlers;

import io.muserver.MuResponse;
import io.muserver.Mutils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;

public interface ResourceProvider {
    boolean exists();

    Long fileSize();

    void sendTo(MuResponse response) throws IOException;
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
            long size = Files.size(localPath);
            if (size >= Integer.MAX_VALUE) {
                return null; // this causes problems due to an overflow somewhere....
            }
            return size;
        } catch (IOException e) {
            System.out.println("Error finding file size: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void sendTo(MuResponse response) throws IOException {
        try (OutputStream os = response.outputStream()) {
            Files.copy(localPath, os);
        }
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

    @Override
    public void sendTo(MuResponse response) throws IOException {
        try (OutputStream out = response.outputStream()) {
            Mutils.copy(info.getInputStream(), out, 8192);
        }
    }

}