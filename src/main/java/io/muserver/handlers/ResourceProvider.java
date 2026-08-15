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

import org.jspecify.annotations.Nullable;

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

    static ClassLoader defaultClassLoader() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        return classLoader == null ? ResourceProviderFactory.class.getClassLoader() : classLoader;
    }

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
        return classpathBased(classpathBase, defaultClassLoader());
    }

    static ResourceProviderFactory classpathBased(String classpathBase, ClassLoader classLoader) {
        ClasspathCache classpathCache = new ClasspathCache(classpathBase, classLoader);
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
    private final String resourceName;
    private final ClassLoader classLoader;
    private final Map<String, ClasspathResourceProvider> all = new HashMap<>();

    ClasspathCache(String basePath, ClassLoader classLoader) {
        this.basePath = basePath;
        this.resourceName = Mutils.trim(basePath, "/");
        this.classLoader = classLoader;
    }

    void cacheItems() throws URISyntaxException, IOException {
        Set<String> seenRoots = new HashSet<>();
        cacheFromFilesystemRoots(seenRoots);
        cacheFromDirectResources(seenRoots);
        cacheFromJarManifests(seenRoots);
    }

    Set<String> immediateSubdirectoryNames() {
        Set<String> directories = new LinkedHashSet<>();
        for (Map.Entry<String, ClasspathResourceProvider> entry : all.entrySet()) {
            String relativePath = entry.getKey();
            if (relativePath.isEmpty()) {
                continue;
            }
            int slashIndex = relativePath.indexOf('/');
            if (slashIndex > 0) {
                directories.add(relativePath.substring(0, slashIndex));
            } else if (entry.getValue().isDirectory()) {
                directories.add(relativePath);
            }
        }
        return directories;
    }

    private void cacheFromFilesystemRoots(Set<String> seenRoots) throws IOException, URISyntaxException {
        Enumeration<URL> resources = classLoader.getResources("");
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            if (!"file".equals(url.getProtocol())) {
                continue;
            }
            Path classpathRoot = Paths.get(url.toURI());
            Path rootPath = resourceName.isEmpty() ? classpathRoot : classpathRoot.resolve(resourceName);
            cacheRootIfPresent(rootPath, seenRoots);
        }
    }

    private void cacheFromDirectResources(Set<String> seenRoots) throws IOException, URISyntaxException {
        if (resourceName.isEmpty()) {
            return;
        }
        Enumeration<URL> resources = classLoader.getResources(resourceName);
        while (resources.hasMoreElements()) {
            cacheRootIfPresent(pathForResource(resources.nextElement()), seenRoots);
        }
    }

    private void cacheFromJarManifests(Set<String> seenRoots) throws IOException {
        Enumeration<URL> manifests = classLoader.getResources("META-INF/MANIFEST.MF");
        while (manifests.hasMoreElements()) {
            URL url = manifests.nextElement();
            if (!"jar".equals(url.getProtocol())) {
                continue;
            }
            try {
                cacheRootIfPresent(pathForJar(url), seenRoots);
            } catch (URISyntaxException e) {
                throw new IOException("Could not inspect classpath jar " + url, e);
            }
        }
    }

    private void cacheRootIfPresent(Path rootPath, Set<String> seenRoots) throws IOException {
        if (!Files.exists(rootPath)) {
            return;
        }
        String rootKey = rootPath.toUri().toString();
        if (!seenRoots.add(rootKey)) {
            return;
        }
        cachePathTree(rootPath);
    }

    private void cachePathTree(Path rootPath) throws IOException {
        try (Stream<Path> walk = Files.walk(rootPath)) {
            for (Iterator<Path> it = walk.iterator(); it.hasNext(); ) {
                Path cur = it.next();
                String relativePath = rootPath.relativize(cur).toString().replace('\\', '/');

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

    private Path pathForResource(URL resource) throws URISyntaxException, IOException {
        if ("jar".equals(resource.getProtocol())) {
            return pathForJar(resource);
        }
        return Paths.get(resource.toURI());
    }

    private Path pathForJar(URL resource) throws IOException, URISyntaxException {
        URI jarUri = URI.create("jar:" + jarFileUrl(resource).toURI());
        FileSystem zipFileSystem;
        try {
            zipFileSystem = FileSystems.getFileSystem(jarUri);
        } catch (FileSystemNotFoundException e) {
            try {
                zipFileSystem = FileSystems.newFileSystem(jarUri, Collections.emptyMap());
            } catch (FileSystemAlreadyExistsException e2) {
                throw new MuException("Cannot create the classpath handler as the Zip File System for this jar file has already been created");
            }
        }
        return zipFileSystem.getPath(basePath);
    }

    private URL jarFileUrl(URL resource) throws IOException {
        return ((java.net.JarURLConnection) resource.openConnection()).getJarFileURL();
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
