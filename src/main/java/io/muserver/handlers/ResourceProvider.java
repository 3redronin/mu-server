package io.muserver.handlers;

import io.muserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

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
        return relativePath -> new AsyncFileProvider(baseDirectory, relativePath);
    }

    static ResourceProviderFactory classpathBased(String classpathBase) {
        ClasspathCache classpathCache = new ClasspathCache(classpathBase);
        try {
            classpathCache.cacheItems();
        } catch (Exception e) {
            throw new MuException("Error while creating classpath provider", e);
        }
        return classpathCache;
    }
}


class ClasspathCache implements ResourceProviderFactory {
    private final String basePath;
    private final Map<String, ClasspathResourceProvider> all = new HashMap<>();

    ClasspathCache(String basePath) {
        this.basePath = basePath;
    }

    void cacheItems() throws URISyntaxException, IOException {
        URL resource = ClasspathCache.class.getResource(basePath);
        if (resource != null) {
            URI uri = resource.toURI();
            Path myPath;
            if (uri.getScheme().equals("jar")) {
                FileSystem fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
                myPath = fileSystem.getPath(basePath);
            } else {
                myPath = Paths.get(uri);
            }
            Stream<Path> walk = Files.walk(myPath);
            for (Iterator<Path> it = walk.iterator(); it.hasNext(); ) {
                Path cur = it.next();
                String relativePath = myPath.relativize(cur).toString().replace('\\', '/');

                boolean exists = Files.exists(cur);
                boolean directory = exists && Files.isDirectory(cur);

                Long size;
                try {
                    size = Files.size(cur);
                } catch (IOException e) {
                    size = null;
                }
                Date lastModified;
                try {
                    lastModified = new Date(Files.getLastModifiedTime(cur).toMillis());
                } catch (IOException e) {
                    lastModified = null;
                }
                ClasspathResourceProvider crp = new ClasspathResourceProvider(exists, directory, size, lastModified, cur, null);
                all.put(relativePath, crp);
            }
        }
    }


    @Override
    public ResourceProvider get(String relativePath) {
        if (relativePath.startsWith("./")) {
            relativePath = relativePath.substring(1);
        }
        relativePath = Mutils.trim(relativePath, "/");
        ClasspathResourceProvider cur = all.get(relativePath);
        if (cur == null) {
            return nullProvider;
        }
        return cur.newWithInputStream();
    }


    private static final ResourceProvider nullProvider = new ResourceProvider() {
        public boolean exists() {
            return false;
        }

        public boolean isDirectory() {
            return false;
        }

        public Long fileSize() {
            return null;
        }

        public Date lastModified() {
            return null;
        }

        public boolean skipIfPossible(long bytes) {
            return false;
        }

        public void sendTo(MuRequest request, MuResponse response, boolean sendBody, long maxLen) {
        }
    };
}


class AsyncFileProvider implements ResourceProvider, CompletionHandler<Integer, Object> {
    private static final Logger log = LoggerFactory.getLogger(AsyncFileProvider.class);
    private final Path localPath;
    private AsynchronousFileChannel channel;
    private long curPos = 0;
    private ByteBuffer buf;
    private AsyncHandle handle;
    private long maxLen;
    private long bytesSent = 0;

    AsyncFileProvider(Path baseDirectory, String relativePath) {
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
            if (size == 0L && isDirectory()) {
                return null;
            }
            return size;
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
            this.maxLen = maxLen;
            handle = request.handleAsync();
            channel = AsynchronousFileChannel.open(localPath, StandardOpenOption.READ);
            buf = ByteBuffer.allocate(8192);
            channel.read(buf, curPos, handle, this);
        } else {
            response.outputStream();
        }
    }

    @Override
    public void completed(Integer bytesRead, Object a) {
        buf.flip();
        if (bytesRead == -1) {
            handle.complete();
            closeChannelQuietly();
        } else {

            // for range requests, more bytes may be read than should be written, so the write is limited
            long remaining = maxLen - bytesSent;
            if (remaining < buf.limit()) {
                buf.limit((int) remaining);
            }

            handle.write(buf, new WriteCallback() {
                @Override
                public void onFailure(Throwable reason) {
                    // client probably disconnected... no big deal
                    closeChannelQuietly();
                    handle.complete();
                }

                @Override
                public void onSuccess() {
                    buf.clear();
                    curPos += bytesRead;
                    bytesSent += bytesRead;
                    channel.read(buf, curPos, null, AsyncFileProvider.this);
                }
            });
        }
    }

    private void closeChannelQuietly() {
        try {
            channel.close();
        } catch (IOException e) {
            log.debug("Error while closing file channel " + localPath, e);
        }
    }

    @Override
    public void failed(Throwable exc, Object a) {
        log.info("File read failure for " + localPath, exc);
        handle.complete(exc);
    }
}

class ClasspathResourceProvider implements ResourceProvider {
    private final boolean exists;
    private final boolean isDir;
    private final Long fileSize;
    private final Date lastModified;
    private final Path path;
    private final InputStream inputStream;

    ClasspathResourceProvider(boolean exists, boolean isDir, Long fileSize, Date lastModified, Path path, InputStream inputStream) {
        this.exists = exists;
        this.isDir = isDir;
        this.path = path;
        this.inputStream = inputStream;
        this.fileSize = isDir ? null : fileSize;
        this.lastModified = lastModified;
    }

    ClasspathResourceProvider newWithInputStream() {
        InputStream inputStream;
        try {
            inputStream = isDir ? null : Files.newInputStream(path, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new MuException("Error while opening " + path + " from the classpath", e);
        }
        return new ClasspathResourceProvider(exists, isDir, fileSize, lastModified, path, inputStream);
    }

    public boolean exists() {
        return exists;
    }

    @Override
    public boolean isDirectory() {
        return isDir;
    }

    @Override
    public Long fileSize() {
        return fileSize;
    }

    @Override
    public Date lastModified() {
        return lastModified;
    }

    @Override
    public boolean skipIfPossible(long bytes) {
        if (bytes > 0) {
            long totalSkipped = 0;
            while (totalSkipped < bytes) {
                long skipped;
                try {
                    skipped = inputStream.skip(bytes);
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
        if (sendBody) {

            try (OutputStream out = response.outputStream()) {
                byte[] buffer = new byte[8192];
                long soFar = 0;
                int read;
                while (soFar < maxLen && (read = inputStream.read(buffer)) > -1) {
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