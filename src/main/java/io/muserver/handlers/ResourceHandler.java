package io.muserver.handlers;

import io.muserver.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static io.muserver.handlers.ResourceType.DEFAULT_EXTENSION_MAPPINGS;

public class ResourceHandler implements MuHandler {
    private final Map<String, ResourceType> extensionToResourceType;
    private final String pathToServeFrom;
    private final String defaultFile;
    private final ResourceProviderFactory resourceProviderFactory;

    public ResourceHandler(ResourceProviderFactory resourceProviderFactory, String pathToServeFrom, String defaultFile, Map<String, ResourceType> extensionToResourceType) {
        this.resourceProviderFactory = resourceProviderFactory;
        this.pathToServeFrom = pathToServeFrom;
        this.extensionToResourceType = extensionToResourceType;
        this.defaultFile = defaultFile;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        String requestPath = request.relativePath();
        if (!requestPath.startsWith(pathToServeFrom)) {
            return false;
        }
        if (requestPath.endsWith("/") && defaultFile != null) {
            requestPath += defaultFile;
        }

        String pathWithoutWebPrefix = pathToServeFrom.equals("/")
            ? requestPath
            : requestPath.substring(pathToServeFrom.length());

        ResourceProvider provider = resourceProviderFactory.get(pathWithoutWebPrefix);
        if (!provider.exists()) {
            return false;
        }
        if (provider.isDirectory()) {
            String goingTo = request.uri().getPath() + "/";
            response.redirect(goingTo);
        } else {
            String filename = requestPath.substring(requestPath.lastIndexOf('/'));
            addHeaders(response, filename);
            provider.sendTo(response, request.method() != Method.HEAD);
        }
        return true;
    }

    private void addHeaders(MuResponse response, String fileName) {
        int ind = fileName.lastIndexOf('.');
        ResourceType type;
        if (ind == -1) {
            type = ResourceType.DEFAULT;
        } else {
            String extension = fileName.substring(ind + 1).toLowerCase();
            type = extensionToResourceType.getOrDefault(extension, ResourceType.DEFAULT);
        }
        response.contentType(type.mimeType);
        response.headers().add(type.headers);
    }

    public static class Builder implements MuHandlerBuilder<ResourceHandler> {
        private Map<String, ResourceType> extensionToResourceType = DEFAULT_EXTENSION_MAPPINGS;
        private String pathToServeFrom = "/";
        private String defaultFile = "index.html";
        private ResourceProviderFactory resourceProviderFactory;

        public Builder withExtensionToResourceType(Map<String, ResourceType> extensionToResourceType) {
            this.extensionToResourceType = extensionToResourceType;
            return this;
        }

        public Builder withPathToServeFrom(String pathToServeFrom) {
            this.pathToServeFrom = pathToServeFrom;
            return this;
        }

        public Builder withDefaultFile(String defaultFile) {
            this.defaultFile = defaultFile;
            return this;
        }

        public Builder withResourceProviderFactory(ResourceProviderFactory resourceProviderFactory) {
            this.resourceProviderFactory = resourceProviderFactory;
            return this;
        }

        public ResourceHandler build() {
            if (resourceProviderFactory == null) {
                throw new IllegalStateException("No resourceProviderFactory has been set");
            }
            return new ResourceHandler(resourceProviderFactory, pathToServeFrom, defaultFile, extensionToResourceType);
        }
    }

    public static ResourceHandler.Builder fileHandler(String directoryPath) {
        return fileHandler(Paths.get(directoryPath));
    }

    public static ResourceHandler.Builder fileHandler(File baseDirectory) {
        return fileHandler(baseDirectory.toPath());
    }

    public static ResourceHandler.Builder fileHandler(Path path) {
        return new Builder().withResourceProviderFactory(ResourceProviderFactory.fileBased(path));
    }

    public static ResourceHandler.Builder classpathHandler(String classpathRoot) {
        return new Builder().withResourceProviderFactory(ResourceProviderFactory.classpathBased(classpathRoot));
    }

    /**
     * Creates a resource handler that serves from the file system if the directory exists; otherwise from the class path.
     * <p>
     * A common use case is for when you want to serve from the file path at development time (so you can update
     * files without restarting) but at deploy time resources are packaged in an uber jar.
     * @param fileRootIfExists A path to a directory holding static content, which may not exist, e.g. <code>src/main/resources/web</code>
     * @param classpathRoot A classpath path to a directory holding static content, e.g. <code>/web</code>
     * @return Returns a file-based resource handler builder or a classpath-based one.
     */
    public static ResourceHandler.Builder fileOrClasspath(String fileRootIfExists, String classpathRoot) {
        Path path = Paths.get(fileRootIfExists);
        if (Files.isDirectory(path)) {
            return fileHandler(path);
        } else {
            return classpathHandler(classpathRoot);
        }
    }

}
