package io.muserver.handlers;

import io.muserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * A handler to serve static content. To create a handler, using {@link ResourceHandlerBuilder#fileOrClasspath(String, String)},
 * {@link ResourceHandlerBuilder#classpathHandler(String)}, {@link ResourceHandlerBuilder#fileHandler(File)} or one of its variants.
 */
public class ResourceHandler implements MuHandler {
    private static final Logger log = LoggerFactory.getLogger(ResourceHandler.class);

    private final Map<String, ResourceType> extensionToResourceType;
    private final String defaultFile;
    private final boolean directoryListingEnabled;
    private final ResourceProviderFactory resourceProviderFactory;
    private final String directoryListingCss;
    private final DateTimeFormatter dateFormatter;
    private final ResourceCustomizer resourceCustomizer;
    private final BareDirectoryRequestAction bareDirectoryRequestAction;

    ResourceHandler(ResourceProviderFactory resourceProviderFactory, String defaultFile, Map<String, ResourceType> extensionToResourceType, boolean directoryListingEnabled, String directoryListingCss, DateTimeFormatter dateFormatter, ResourceCustomizer resourceCustomizer, BareDirectoryRequestAction bareDirectoryRequestAction) {
        this.resourceProviderFactory = resourceProviderFactory;
        this.extensionToResourceType = extensionToResourceType;
        this.defaultFile = defaultFile;
        this.directoryListingEnabled = directoryListingEnabled;
        this.directoryListingCss = directoryListingCss;
        this.dateFormatter = dateFormatter;
        this.resourceCustomizer = resourceCustomizer;
        this.bareDirectoryRequestAction = bareDirectoryRequestAction;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws IOException {
        String requestPath = request.relativePath();
        if (requestPath.endsWith("/") && defaultFile != null) {
            requestPath += defaultFile;
        }
        String decodedRelativePath = Mutils.urlDecode(requestPath);

        ResourceProvider provider = resourceProviderFactory.get(decodedRelativePath);
        if (!provider.exists()) {
            if (directoryListingEnabled) {
                provider = resourceProviderFactory.get(Mutils.urlDecode(request.relativePath()));
                if (!provider.isDirectory()) {
                    return false;
                }
            } else {
                return false;
            }
        }

        if (provider.isDirectory()) {
            if (!request.relativePath().endsWith("/")) {
                switch (bareDirectoryRequestAction) {
                    case REDIRECT_WITH_TRAILING_SLASH:
                        response.redirect(request.uri().getRawPath() + "/");
                        return true;
                    case TREAT_AS_NOT_FOUND:
                        return false;
                    default: throw new IllegalStateException("Unreachable");
                }
            }
            if (directoryListingEnabled) {
                listDirectory(request, response, provider);
            } else {
                return false;
            }
        } else {
            String filename = requestPath.substring(requestPath.lastIndexOf('/'));
            Date lastModified = provider.lastModified();
            Long totalSize = provider.fileSize();
            addHeaders(response, filename, totalSize, lastModified, request);
            boolean sendBody = request.method() != Method.HEAD;

            String ims = request.headers().get(HeaderNames.IF_MODIFIED_SINCE);
            if (ims != null) {
                try {
                    long lastModTime = lastModified.getTime() / 1000;
                    long lastAccessed = Mutils.fromHttpDate(ims).getTime() / 1000;
                    if (lastModTime <= lastAccessed) {
                        response.status(304);
                        sendBody = false;
                    }
                } catch (DateTimeParseException e) {
                    log.info("Ignoring cache check due to invalid If-Modified-Since header value: " + ims);
                }
            }

            String rh = request.headers().get("range");
            long maxAmountToSend = totalSize != null ? totalSize : Long.MAX_VALUE;
            if (rh != null && totalSize != null && response.status() != HttpStatus.NOT_MODIFIED_304) {
                try {
                    List<BytesRange> requestedRanges = BytesRange.parse(totalSize, rh);
                    if (requestedRanges.size() == 1) {
                        BytesRange range = requestedRanges.get(0);
                        boolean couldSkip = provider.skipIfPossible(range.from);
                        if (couldSkip) {
                            response.status(206);
                            maxAmountToSend = range.length();
                            response.headers().set(HeaderNames.CONTENT_LENGTH, maxAmountToSend);
                            response.headers().set(HeaderNames.CONTENT_RANGE, range.toString());
                        }
                    }
                } catch (IllegalArgumentException e) {
                    log.info("Ignoring range request due to invalid Range header value: " + rh);
                }
            }
            try {
                provider.sendTo(request, response, sendBody, maxAmountToSend);
            } catch (IOException | IllegalStateException ignored) {
                log.debug(request + " cancelled before full response sent to the client");
            }
        }

        return true;
    }

    private void listDirectory(MuRequest request, MuResponse response, ResourceProvider provider) throws IOException {
        response.contentType(ContentTypes.TEXT_HTML_UTF8);
        response.headers().set("X-UA-Compatible", "IE=edge");

        try (OutputStreamWriter osw = new OutputStreamWriter(response.outputStream(), StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(osw, 8192)) {
            writer.write("<!DOCTYPE html>\n");
            new DirectoryLister(writer, provider, request.contextPath(), request.relativePath(), directoryListingCss, dateFormatter).render();
        }
    }


    private void addHeaders(MuResponse response, String fileName, Long fileSize, Date lastModified, MuRequest request) {
        int ind = fileName.lastIndexOf('.');
        ResourceType type;
        if (ind == -1) {
            type = ResourceType.DEFAULT;
        } else {
            String extension = fileName.substring(ind + 1).toLowerCase();
            type = extensionToResourceType.getOrDefault(extension, ResourceType.DEFAULT);
        }
        response.contentType(type.mimeType());
        Headers headers = response.headers();
        headers.set(HeaderNames.ACCEPT_RANGES, HeaderValues.BYTES);
        if (fileSize != null) {
            headers.set(HeaderNames.CONTENT_LENGTH, fileSize);
        }
        if (lastModified != null) {
            headers.set(HeaderNames.LAST_MODIFIED, Mutils.toHttpDate(lastModified));
        }
        headers.add(type.headers());
        if (this.resourceCustomizer != null) {
            this.resourceCustomizer.beforeHeadersSent(request, headers);
        }
    }

    @Override
    public String toString() {
        return "ResourceHandler{" +
            "defaultFile='" + defaultFile + '\'' +
            ", directoryListingEnabled=" + directoryListingEnabled +
            ", resourceProviderFactory=" + resourceProviderFactory +
            '}';
    }
}
