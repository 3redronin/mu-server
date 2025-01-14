package io.muserver.handlers;

import io.muserver.MuHandlerBuilder;
import io.muserver.rest.RestHandlerBuilder;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

import static io.muserver.handlers.ResourceType.DEFAULT_EXTENSION_MAPPINGS;

/**
 * <p>Used to create a {@link ResourceHandler} for serving static files.</p>
 * <p>In order to serve from the filesystem during development, and from classpath and deploy time, see
 * {@link #fileOrClasspath(String, String)}</p>
 */
public class ResourceHandlerBuilder implements MuHandlerBuilder<ResourceHandler> {

    private @Nullable DateTimeFormatter directoryListingDateFormatter;
    private Map<String, ResourceType> extensionToResourceType = DEFAULT_EXTENSION_MAPPINGS;
    private @Nullable String defaultFile = "index.html";
    private @Nullable ResourceProviderFactory resourceProviderFactory;
    private boolean directoryListingEnabled = false;
    private @Nullable String directoryListingCss = null;
    private @Nullable ResourceCustomizer resourceCustomizer = null;
    private BareDirectoryRequestAction bareDirectoryRequestAction = BareDirectoryRequestAction.REDIRECT_WITH_TRAILING_SLASH;

    /**
     * Specify custom filename extension to mime-type mappings. By default {@link ResourceType#DEFAULT_EXTENSION_MAPPINGS}
     * are used.
     * @param extensionToResourceType The mappings to use.
     * @return This builder
     */
    public ResourceHandlerBuilder withExtensionToResourceType(Map<String, ResourceType> extensionToResourceType) {
        this.extensionToResourceType = extensionToResourceType;
        return this;
    }

    /**
     * Specifies the file to use when a request such as <code>/web/</code> is made. Defaults to <code>index.html</code>
     * @param defaultFile The default file to use when a directory is requested, or <code>null</code> for no default.
     * @return This builder
     */
    public ResourceHandlerBuilder withDefaultFile(@Nullable String defaultFile) {
        this.defaultFile = defaultFile;
        return this;
    }

    ResourceHandlerBuilder withResourceProviderFactory(ResourceProviderFactory resourceProviderFactory) {
        this.resourceProviderFactory = resourceProviderFactory;
        return this;
    }

    /**
     * Specifies whether or not to allow directory listing. This is disabled by default.
     * <p>Note that directory listings will not show if there is a file in the directory
     * that matches the {@link #withDefaultFile(String)} setting. Set that to <code>null</code>
     * if you do not want the default file (e.g. <code>index.html</code>) to display.</p>
     * @param enabled <code>true</code> to turn it on; <code>false</code> to disable it.
     * @return This builder
     */
    public ResourceHandlerBuilder withDirectoryListing(boolean enabled) {
        this.directoryListingEnabled = enabled;
        return this;
    }

    /**
     * Specifies a custom date format for the "Last modified" column when directory listing is enabled.
     * @param dateTimeFormatter A format object, or null to use the default
     * @return This builder
     */
    public ResourceHandlerBuilder withDirectoryListingDateFormatter(@Nullable DateTimeFormatter dateTimeFormatter) {
        this.directoryListingDateFormatter = dateTimeFormatter;
        return this;
    }

    /**
     * Specifies CSS to use for the HTML directory listing page, if directory listing is enabled.
     * @param css CSS styles to use, or null for the default
     * @return This builder
     */
    public ResourceHandlerBuilder withDirectoryListingCSS(@Nullable String css) {
        this.directoryListingCss = css;
        return this;
    }

    /**
     * Registers a hook to intercept responses, allowing things such as response header customization based on the request.
     * @param resourceCustomizer A class to intercept responses
     * @return This builder
     */
    public ResourceHandlerBuilder withResourceCustomizer(@Nullable ResourceCustomizer resourceCustomizer) {
        this.resourceCustomizer = resourceCustomizer;
        return this;
    }

    /**
     * @return The current value of this property
     */
    public @Nullable DateTimeFormatter directoryListingDateFormatter() {
        return directoryListingDateFormatter;
    }

    /**
     * @return The current value of this property
     */
    public Map<String, ResourceType> extensionToResourceType() {
        return Collections.unmodifiableMap(extensionToResourceType);
    }

    /**
     * @return The current value of this property
     */
    public @Nullable String defaultFile() {
        return defaultFile;
    }

    /**
     * @return The current value of this property
     */
    public boolean directoryListingEnabled() {
        return directoryListingEnabled;
    }

    /**
     * @return The current value of this property
     */
    public @Nullable String directoryListingCss() {
        return directoryListingCss;
    }

    /**
     * @return The current value of this property
     */
    public @Nullable ResourceCustomizer resourceCustomizer() {
        return resourceCustomizer;
    }

    /**
     * @return The action to take when a directory is requested without a trailing slash
     */
    public BareDirectoryRequestAction bareDirectoryRequestAction() {
        return bareDirectoryRequestAction;
    }

    /**
     * Specifies the action to take when a directory is requested without a trailing slash.
     *
     * <p>The default is {@link BareDirectoryRequestAction#REDIRECT_WITH_TRAILING_SLASH} which means a request
     * to <code>/{dirname}</code> will be redirected to <code>/{dirname}/</code></p>
     * @param action The action to take
     * @return this builder
     */
    public ResourceHandlerBuilder withBareDirectoryRequestAction(BareDirectoryRequestAction action) {
        if (action == null) {
            throw new IllegalArgumentException("BareDirectoryRequestAction cannot be null");
        }
        this.bareDirectoryRequestAction = action;
        return this;
    }



    /**
     * Creates the handler
     * @return The built handler
     */
    public ResourceHandler build() {
        if (resourceProviderFactory == null) {
            throw new IllegalStateException("No resourceProviderFactory has been set");
        }
        String css = this.directoryListingCss;
        if (directoryListingEnabled && css == null) {
            InputStream cssStream = RestHandlerBuilder.class.getResourceAsStream("/io/muserver/resources/api.css");
            assert cssStream != null;
            Scanner scanner = new Scanner(cssStream, StandardCharsets.UTF_8).useDelimiter("\\A");
            css = scanner.next();
            scanner.close();
        }

        DateTimeFormatter formatterToUse = this.directoryListingDateFormatter;
        if (directoryListingEnabled && formatterToUse == null) {
            formatterToUse = DateTimeFormatter.ofPattern("yyyy/MM/dd hh:mm:ss")
                .withLocale(Locale.US)
                .withZone(ZoneId.systemDefault());
        }

        return new ResourceHandler(resourceProviderFactory, defaultFile, extensionToResourceType, directoryListingEnabled, css, formatterToUse, this.resourceCustomizer, this.bareDirectoryRequestAction);
    }


    /**
     * Creates a handler that serves files from the given directory.
     * @param directoryPath The directory.
     * @return A new builder.
     */
    public static ResourceHandlerBuilder fileHandler(String directoryPath) {
        return fileHandler(Paths.get(directoryPath));
    }

    /**
     * Creates a handler that serves files from the given directory.
     * @param baseDirectory The directory.
     * @return A new builder.
     */
    public static ResourceHandlerBuilder fileHandler(File baseDirectory) {
        return fileHandler(baseDirectory.toPath());
    }

    /**
     * Creates a handler that serves files from the given directory.
     * @param path The directory.
     * @return A new builder.
     */
    public static ResourceHandlerBuilder fileHandler(Path path) {
        return new ResourceHandlerBuilder().withResourceProviderFactory(ResourceProviderFactory.fileBased(path));
    }


    /**
     * Creates a handler that serves files from the classpath..
     * @param classpathRoot A classpath directory, such as <code>/web</code>
     * @return A new builder.
     */
    public static ResourceHandlerBuilder classpathHandler(String classpathRoot) {
        return new ResourceHandlerBuilder().withResourceProviderFactory(ResourceProviderFactory.classpathBased(classpathRoot));
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
    public static ResourceHandlerBuilder fileOrClasspath(String fileRootIfExists, String classpathRoot) {
        Path path = Paths.get(fileRootIfExists);
        if (Files.isDirectory(path)) {
            return fileHandler(path);
        } else {
            return classpathHandler(classpathRoot);
        }
    }
}
