package io.muserver.handlers;

import io.muserver.*;
import org.jspecify.annotations.Nullable;
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

    @Nullable Long fileSize();

    @Nullable Date lastModified();

    boolean skipIfPossible(long bytes);

    void sendTo(MuRequest request, MuResponse response, boolean sendBody, long maxLen) throws IOException;

    Stream<Path> listFiles() throws IOException;
}

interface ResourceProviderFactory {

    ResourceProvider get(String relativePath);

    static ResourceProviderFactory fileBased(Path baseDirectory) {
        if (!Files.isDirectory(baseDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new MuException(baseDirectory + " is not a directory");
        }
        return new ResourceProviderFactory() {
            @Override
            public ResourceProvider get(String relativePath) {
                return new AsyncFileProvider(baseDirectory, relativePath);
            }

            @Override
            public String toString() {
                return "AsyncFileProviderFactory{" +
                    "baseDirectory='" + baseDirectory + '\'' +
                    '}';
            }
        };
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
                FileSystem zipFileSystem;
                try {
                    zipFileSystem = FileSystems.getFileSystem(uri);
                } catch (FileSystemNotFoundException e) {
                    try {
                        zipFileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
                    } catch (FileSystemAlreadyExistsException e2) {
                        throw new MuException("Cannot create the classpath handler as the Zip File System for this jar file has already been created");
                    }
                }
                myPath = zipFileSystem.getPath(basePath);
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
            walk.close();
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

    @Override
    public String toString() {
        return "ClasspathCache{" +
            "basePath='" + basePath + '\'' +
            '}';
    }

    private static final ResourceProvider nullProvider = new ResourceProvider() {
        @Override
        public boolean exists() {
            return false;
        }

        @Override
        public boolean isDirectory() {
            return false;
        }

        @Override
        public @Nullable Long fileSize() {
            return null;
        }

        @Override
        public @Nullable Date lastModified() {
            return null;
        }

        @Override
        public boolean skipIfPossible(long bytes) {
            return false;
        }

        @Override
        public void sendTo(MuRequest request, MuResponse response, boolean sendBody, long maxLen) {
        }

        @Override
        public Stream<Path> listFiles() {
            return Stream.empty();
        }
    };
}


class AsyncFileProvider implements ResourceProvider, CompletionHandler<Integer, Object> {
    private static final Logger log = LoggerFactory.getLogger(AsyncFileProvider.class);
    private final Path localPath;
    private @Nullable AsynchronousFileChannel channel;
    private long curPos = 0;
    private @Nullable ByteBuffer buf;
    private @Nullable AsyncHandle handle;
    private long maxLen;
    private long bytesSent = 0;

    AsyncFileProvider(Path baseDirectory, String relativePath) {
        if (relativePath.startsWith("/")) {
            relativePath = "." + relativePath;
        }
        this.localPath = baseDirectory.resolve(relativePath);
    }

    @Override
    public boolean exists() {
        return Files.exists(localPath);
    }

    @Override
    public boolean isDirectory() {
        return Files.isDirectory(localPath);
    }

    @Override
    public @Nullable Long fileSize() {
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
    public @Nullable Date lastModified() {
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
            requiredChannel().read(requiredBuffer(), curPos, requiredHandle(), this);
        }
    }

    @Override
    public Stream<Path> listFiles() throws IOException {
        return Files.list(localPath);
    }

    @Override
    public void completed(Integer bytesRead, Object a) {
        ByteBuffer buffer = requiredBuffer();
        AsyncHandle asyncHandle = requiredHandle();
        buffer.flip();
        if (bytesRead == -1) {
            asyncHandle.complete();
            closeChannelQuietly();
        } else {

            // for range requests, more bytes may be read than should be written, so the write is limited
            long remaining = Math.max(0, maxLen - bytesSent);
            if (remaining < buffer.limit()) {
                buffer.limit((int) remaining);
            }

            asyncHandle.write(buffer, error -> {
                if (error == null) {
                    buffer.clear();
                    curPos += bytesRead;
                    bytesSent += bytesRead;
                    requiredChannel().read(buffer, curPos, null, AsyncFileProvider.this);
                } else {
                    closeChannelQuietly();
                    asyncHandle.complete(error);
                }
            });
        }
    }

    private void closeChannelQuietly() {
        try {
            requiredChannel().close();
        } catch (IOException e) {
            log.debug("Error while closing file channel " + localPath, e);
        }
    }

    @Override
    public void failed(Throwable exc, Object a) {
        log.info("File read failure for " + localPath, exc);
        requiredHandle().complete(exc);
    }

    private AsynchronousFileChannel requiredChannel() {
        return Objects.requireNonNull(channel, "File transfer has not been initialized");
    }

    private ByteBuffer requiredBuffer() {
        return Objects.requireNonNull(buf, "File transfer has not been initialized");
    }

    private AsyncHandle requiredHandle() {
        return Objects.requireNonNull(handle, "File transfer has not been initialized");
    }
}

class ClasspathResourceProvider implements ResourceProvider {
    private final boolean exists;
    private final boolean isDir;
    private final @Nullable Long fileSize;
    private final @Nullable Date lastModified;
    private final Path path;
    private final @Nullable InputStream inputStream;

    ClasspathResourceProvider(boolean exists, boolean isDir, @Nullable Long fileSize, @Nullable Date lastModified, Path path, @Nullable InputStream inputStream) {
        this.exists = exists;
        this.isDir = isDir;
        this.path = path;
        this.inputStream = inputStream;
        this.fileSize = isDir ? null : fileSize;
        this.lastModified = lastModified;
    }

    ClasspathResourceProvider newWithInputStream() {
        @Nullable InputStream inputStream;
        try {
            inputStream = isDir ? null : Files.newInputStream(path, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new MuException("Error while opening " + path + " from the classpath", e);
        }
        return new ClasspathResourceProvider(exists, isDir, fileSize, lastModified, path, inputStream);
    }

    @Override
    public boolean exists() {
        return exists;
    }

    @Override
    public boolean isDirectory() {
        return isDir;
    }

    @Override
    public @Nullable Long fileSize() {
        return fileSize;
    }

    @Override
    public @Nullable Date lastModified() {
        return lastModified;
    }

    @Override
    public boolean skipIfPossible(long bytes) {
        assert inputStream != null; // only null for directories where this isn't used
        if (bytes > 0) {
            long totalSkipped = 0;
            while (totalSkipped < bytes) {
                long skipped;
                try {
                    skipped = requiredInputStream().skip(bytes);
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
        try {
            if (sendBody) {

                try (OutputStream out = response.outputStream()) {
                    byte[] buffer = new byte[8192];
                    long soFar = 0;
                    int read;
                    while (soFar < maxLen && (read = requiredInputStream().read(buffer)) > -1) {
                        soFar += read;
                        if (soFar > maxLen) {
                            read -= (int) (soFar - maxLen);
                        }
                        if (read > 0) {
                            out.write(buffer, 0, read);
                        }
                    }
                }
            }
        } finally {
            requiredInputStream().close();
        }
    }

    @Override
    public Stream<Path> listFiles() throws IOException {
        return Files.list(path);
    }

    private InputStream requiredInputStream() {
        return Objects.requireNonNull(inputStream, "This resource has no input stream");
    }
}
