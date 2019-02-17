package io.muserver.handlers;

import io.muserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.net.www.protocol.file.FileURLConnection;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Date;

interface ResourceProvider {
    boolean exists();

    boolean isDirectory();

    Long fileSize();

    Date lastModified();

    boolean skipIfPossible(long bytes);

    void sendTo(MuRequest request, MuResponse response, boolean sendBody, long maxLen) throws IOException;
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


class FileProvider implements ResourceProvider, CompletionHandler<Integer, Object> {
    private static final Logger log = LoggerFactory.getLogger(FileProvider.class);
    private final Path localPath;
    private AsynchronousFileChannel channel;
    private long curPos = 0;
    private ByteBuffer buf;
    private AsyncHandle handle;
    private long maxLen;
    private long bytesSent = 0;

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
            return Files.size(localPath);
        } catch (IOException e) {
            log.error("Error finding file size: " + e.getMessage());
            return null;
        }
    }

    @Override
    public Date lastModified() {
        try {
            return new Date(Files.getLastModifiedTime(localPath).toMillis());
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public boolean skipIfPossible(long bytes) {
        this.curPos = bytes;
        return true;
    }

    @Override
    public void sendTo(MuRequest request, MuResponse response, boolean sendBody, long maxLen) throws IOException {
        if (sendBody) {
            handle = request.handleAsync();
            channel = AsynchronousFileChannel.open(localPath, StandardOpenOption.READ);
            buf = ByteBuffer.allocate(8192);
            channel.read(buf, curPos, handle, this);
            this.maxLen = maxLen;
        } else {
            response.outputStream();
        }
    }

    @Override
    public void completed(Integer bytesRead, Object a) {
        buf.flip();
        if (bytesRead == -1) {
            handle.complete();
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        } else {
            long remaining = maxLen - bytesSent;
            if (remaining < buf.limit()) {
                buf.limit((int)remaining);
            }
            handle.write(buf, new WriteCallback() {
                @Override
                public void onFailure(Throwable reason) {
                    // client probably disconnected... no big deal
                    handle.complete();
                }

                @Override
                public void onSuccess() {
                    buf.clear();
                    curPos += bytesRead;
                    bytesSent += bytesRead;
                    channel.read(buf, curPos, null, FileProvider.this);
                }
            });
        }
    }

    @Override
    public void failed(Throwable exc, Object a) {
        log.info("File read failure for " + localPath, exc);
        handle.complete(exc);
    }
}

class ClasspathResourceProvider implements ResourceProvider {
    private static final Logger log = LoggerFactory.getLogger(ClasspathResourceProvider.class);
    private final URLConnection info;
    private final boolean isDir;

    ClasspathResourceProvider(String classpathBase, String relativePath) {
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

    @Override
    public Long fileSize() {
        if (isDir) {
            return null;
        }
        long size = info.getContentLengthLong();
        return size >= 0 ? size : null;
    }

    @Override
    public Date lastModified() {
        long mod = info.getLastModified();
        return mod >= 0 ? new Date(mod) : null;
    }

    @Override
    public boolean skipIfPossible(long bytes) {
        if (bytes > 0) {
            long totalSkipped = 0;
            while (totalSkipped < bytes) {
                long skipped;
                try {
                    skipped = info.getInputStream().skip(bytes);
                } catch (IOException e) {
                    return false;
                }
                if (skipped <= 0) {
                    return false;
                }
                totalSkipped += skipped;
            }
        }
        return true;
    }

    @Override
    public void sendTo(MuRequest request, MuResponse response, boolean sendBody, long maxLen) throws IOException {
        sendToResponse(response, sendBody, maxLen, info.getInputStream());
    }

    static void sendToResponse(MuResponse response, boolean sendBody, long maxLen, InputStream is) throws IOException {
        if (sendBody) {

            try (OutputStream out = response.outputStream()) {
                byte[] buffer = new byte[8192];
                long soFar = 0;
                int read;
                while (soFar < maxLen && (read = is.read(buffer)) > -1) {
                    soFar += read;
                    if (soFar > maxLen) {
                        read -= soFar - maxLen;
                    }
                    if (read > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        } else {
            response.outputStream();
        }
    }

}