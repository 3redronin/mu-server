package ronin.muserver.handlers;

import ronin.muserver.*;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class ResourceHandler implements MuHandler {
    private final Map<String,ResourceType> extensionToResourceType;
    private final Path directory;
    private final String pathToServeFrom;
    private final String defaultFile;

    public ResourceHandler(String baseDirectoryPath, String pathToServeFrom, String defaultFile, Map<String, ResourceType> extensionToResourceType) {
        this.pathToServeFrom = pathToServeFrom;
        this.directory = Paths.get(baseDirectoryPath);
        this.extensionToResourceType = extensionToResourceType;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new MuException(directory + " is not a directory");
        }
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
        Path localPath = (pathToServeFrom.equals("/"))
            ? directory.resolve("." + requestPath)
            : directory.resolve("." + requestPath.substring(pathToServeFrom.length()));
        if (!Files.isRegularFile(localPath, LinkOption.NOFOLLOW_LINKS)) {
            System.out.println("Could not find " + localPath.normalize());
            return false;
        }
        response.headers().add(HeaderNames.CONTENT_LENGTH, Files.size(localPath));
        addHeaders(response, localPath);
        try (OutputStream out = response.outputStream()) {
            long copy = Files.copy(localPath, out);
            System.out.println("Sent " + copy + " bytes for " + localPath);
        }
        return true;
    }

    private void addHeaders(MuResponse response, Path localPath) {
        String fileName = localPath.getFileName().toString();
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
