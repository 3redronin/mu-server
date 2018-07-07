package io.muserver.handlers;

import io.muserver.MuException;
import io.muserver.MuResponse;
import io.muserver.Mutils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.net.www.protocol.file.FileURLConnection;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

interface ResourceProvider {
    boolean exists();

    boolean isDirectory();

    Long fileSize();

    void sendTo(MuResponse response, boolean sendBody) throws IOException;
}

interface ResourceProviderFactory {

    ResourceProvider get(String relativePath);

    static ResourceProviderFactory fileBased(Path baseDirectory) {
        if (!Files.isDirectory(baseDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new MuException(baseDirectory + " is not a directory");
        }
        return relativePath -> new FileProvider(baseDirectory, relativePath);
    }

    static ResourceProviderFactory classpathBased(String classpathBase) {
        return relativePath -> new ClasspathResourceProvider(classpathBase, relativePath);
    }
}


class FileProvider implements ResourceProvider {
    private static final Logger log = LoggerFactory.getLogger(FileProvider.class);
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
            log.error("Error finding file size: " + e.getMessage());
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
    private static final Logger log = LoggerFactory.getLogger(ClasspathResourceProvider.class);
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
                log.error("Error opening " + resource, e);
                con = null;
            }
        }
        this.info = con;
        boolean isDir = false;
        if (con != null) {
            if (con instanceof JarURLConnection) {
                JarURLConnection juc = (JarURLConnection) con;
                try {
                    isDir = juc.getJarEntry().isDirectory();
                } catch (IOException e) {
                    log.error("Error checking if " + resource + " is a directory", e);
                }
            } else if (con instanceof FileURLConnection) {
                FileURLConnection fuc = (FileURLConnection) con;
                isDir = new File(fuc.getURL().getFile()).isDirectory();
            } else {
                log.warn("Unexpected jar entry type for " + resource + ": " + con.getClass());
            }

        }
        this.isDir = isDir;
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