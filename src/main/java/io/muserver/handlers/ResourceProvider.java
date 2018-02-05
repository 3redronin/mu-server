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

    boolean isDirectory();

    Long fileSize();

    void sendTo(MuResponse response, boolean sendBody) throws IOException;
}

class FileProvider implements ResourceProvider {
    private final Path localPath;

    FileProvider(Path baseDirectory, String relativePath) {
        if (relativePath.startsWith("/")) {
            relativePath = "." + relativePath;
        }
        this.localPath = baseDirectory.resolve(relativePath);
    }

    public boolean exists() {
        return Files.exists(localPath);
    }

    @Override
    public boolean isDirectory() {
        return Files.isDirectory(localPath);
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
    public void sendTo(MuResponse response, boolean sendBody) throws IOException {
        if (sendBody) {
            try (OutputStream os = response.outputStream()) {
                Files.copy(localPath, os);
            }
        } else {
            response.outputStream();
        }
    }

}

class ClasspathResourceProvider implements ResourceProvider {
    private final URLConnection info;
    private final boolean isDir;

    public ClasspathResourceProvider(String classpathBase, String relativePath) {
        URLConnection con;
        String path = Mutils.join(classpathBase, "/", relativePath);
        URL resource = ClasspathResourceProvider.class.getResource(path);
        if (resource == null) {
            con = null;
        } else {
            try {
                con = resource.openConnection();
            } catch (IOException e) {
                System.out.println("Error " + e.getMessage());
                con = null;
            }
        }
        this.info = con;
        // TODO: support files that don't have extensions
        this.isDir = con != null && (path.lastIndexOf(".") < path.lastIndexOf("/"));
    }

    public boolean exists() {
        return info != null;
    }

    @Override
    public boolean isDirectory() {
        return isDir;
    }

    public Long fileSize() {
        long size = info.getContentLengthLong();
        return size >= 0 ? size : null;
    }

    @Override
    public void sendTo(MuResponse response, boolean sendBody) throws IOException {
        if (sendBody) {
            try (OutputStream out = response.outputStream()) {
                Mutils.copy(info.getInputStream(), out, 8192);
            }
        } else {
            response.outputStream();
        }
    }

}