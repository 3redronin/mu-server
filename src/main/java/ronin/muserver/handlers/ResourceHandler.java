package ronin.muserver.handlers;

import ronin.muserver.HeaderNames;
import ronin.muserver.MuHandler;
import ronin.muserver.MuRequest;
import ronin.muserver.MuResponse;

import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;

public class ResourceHandler implements MuHandler {
    private final Map<String,ResourceType> extensionToResourceType;
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
        String requestPath = request.uri().getPath();
        if (!requestPath.startsWith(pathToServeFrom)) {
            return false;
        }
        if (requestPath.endsWith("/") && defaultFile != null) {
            requestPath += defaultFile;
        }

        String pathWithoutWebPrefix = pathToServeFrom.equals("/")
            ? "." + requestPath
            : "." + requestPath.substring(pathToServeFrom.length());

        try (ResourceProvider provider = resourceProviderFactory.get(pathWithoutWebPrefix)) {

            if (!provider.exists()) {
                System.out.println("Could not find " + requestPath);
                return false;
            }
            Long fileSize = provider.fileSize();
            if (fileSize != null) {
                response.headers().add(HeaderNames.CONTENT_LENGTH, fileSize);
            }

            String filename = requestPath.substring(requestPath.lastIndexOf('/'));
            addHeaders(response, filename);

            try (OutputStream out = response.outputStream(16 * 1024)) {
                provider.writeTo(out);
            }
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
        response.headers().set(HeaderNames.CONTENT_TYPE, type.mimeType);
        response.headers().add(type.headers);
    }
}
