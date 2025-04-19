package io.muserver.handlers;

import io.muserver.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

/**
 * <p>Used by the {@link ResourceHandler} to specify a mapping between file name extensions and mime types, and whether they should be gzipped or not.</p>
 * <p>A number of common mappings are provided by default. To specify your custom settings, use {@link ResourceHandlerBuilder#withExtensionToResourceType(Map)}</p>
 */
public class ResourceType {

    /**
     * The mapping of file extensions (such as "jpg") to resource types (such as {@link #IMAGE_JPEG} that mu-server uses by default when serving static content.
     */
    public static final Map<String, ResourceType> DEFAULT_EXTENSION_MAPPINGS;

    private final CharSequence mimeType;

    private final Headers headers;

    private final List<String> extensions;

    private final boolean gzip;

    /**
     * @return The mime type of this resource, such as <code>image/jpeg</code>
     */
    public CharSequence mimeType() {
        return mimeType;
    }

    /**
     * @return The response headers that mu-server will send when serving this resource type
     */
    public Headers headers() {
        return headers;
    }

    /**
     * @return The file name extensions that this resource type is associated with, for example <code>jpg</code> and <code>jpeg</code>
     */
    public List<String> extensions() {
        return extensions;
    }

    /**
     * @return True if by default mu-server gzips this resource type when serving it. (In general text-based resources are gzipped; others aren't.)
     */
    public boolean gzip() {
        return gzip;
    }

    /**
     * Creates a new resource type. This is only required if you want to support resource types not included in {@link #DEFAULT_EXTENSION_MAPPINGS}
     *
     * @param mimeType   The mime type
     * @param headers    The headers to respond with
     * @param gzip       Whether to gzip when serving
     * @param extensions The file extensions for this resource
     */
    public ResourceType(CharSequence mimeType, Headers headers, boolean gzip, List<String> extensions) {
        this.mimeType = mimeType;
        this.headers = headers;
        this.gzip = gzip;
        this.extensions = extensions;
    }

    /**
     * @return Creates and returns a Headers object with a single cache-control header set for 5 minutes.
     */
    public static Headers shortCache() {
        return Headers.create()
            .add(HeaderNames.CACHE_CONTROL, "max-age=300");
    }

    /**
     * @return Creates and returns a Headers object with a single cache-control header set with no-cache.
     */
    public static Headers noCache() {
        return Headers.create()
            .add(HeaderNames.CACHE_CONTROL, HeaderValues.NO_CACHE);
    }

    /**
     * The resource type used when no others match. In this case, {@link ContentTypes#APPLICATION_OCTET_STREAM} is used.
     */
    public static final ResourceType DEFAULT = new ResourceType(ContentTypes.APPLICATION_OCTET_STREAM, shortCache(), false, Collections.emptyList());

    /**
     * <code>audio/aac</code>
     */
    public static final ResourceType AUDIO_AAC = new ResourceType(ContentTypes.AUDIO_AAC, shortCache(), false, singletonList("aac"));
    /**
     * <code>application/x-abiword</code>
     */
    public static final ResourceType APPLICATION_X_ABIWORD = new ResourceType(ContentTypes.APPLICATION_X_ABIWORD, shortCache(), false, singletonList("abw"));
    /**
     * <code>video/x-msvideo</code>
     */
    public static final ResourceType VIDEO_X_MSVIDEO = new ResourceType(ContentTypes.VIDEO_X_MSVIDEO, shortCache(), false, singletonList("avi"));
    /**
     * <code>application/vnd-amazon-ebook</code>
     */
    public static final ResourceType APPLICATION_VND_AMAZON_EBOOK = new ResourceType(ContentTypes.APPLICATION_VND_AMAZON_EBOOK, shortCache(), false, singletonList("azw"));
    /**
     * <code>application/octet-stream</code>
     */
    public static final ResourceType APPLICATION_OCTET_STREAM = new ResourceType(ContentTypes.APPLICATION_OCTET_STREAM, shortCache(), false, asList("bin", "arc"));
    /**
     * <code>application/x-bzip</code>
     */
    public static final ResourceType APPLICATION_X_BZIP = new ResourceType(ContentTypes.APPLICATION_X_BZIP, shortCache(), false, singletonList("bz"));
    /**
     * <code>application/x-bzip2</code>
     */
    public static final ResourceType APPLICATION_X_BZIP2 = new ResourceType(ContentTypes.APPLICATION_X_BZIP2, shortCache(), false, singletonList("bz2"));
    /**
     * <code>application/x-csh</code>
     */
    public static final ResourceType APPLICATION_X_CSH = new ResourceType(ContentTypes.APPLICATION_X_CSH, shortCache(), false, singletonList("csh"));
    /**
     * <code>text/css</code>
     */
    public static final ResourceType TEXT_CSS = new ResourceType(ContentTypes.TEXT_CSS,
        Headers.create()
            .add(HeaderNames.CACHE_CONTROL, "max-age=300")
            .add(HeaderNames.X_CONTENT_TYPE_OPTIONS, HeaderValues.NOSNIFF),
        true, singletonList("css"));
    /**
     * <code>text/plain;charset=utf-8</code>
     */
    public static final ResourceType TEXT_PLAIN = new ResourceType(ContentTypes.TEXT_PLAIN_UTF8, noCache(), true,
        asList("txt", "ini", "gitignore", "gitattributes", "cfg", "log", "out", "text", "properties"));
    /**
     * <code>text/markdown;charset=utf-8</code>
     */
    public static final ResourceType TEXT_MARKDOWN = new ResourceType(ContentTypes.TEXT_MARKDOWN_UTF8, shortCache(), true, singletonList("md"));
    /**
     * <code>text/csv;charset=utf-8</code>
     */
    public static final ResourceType TEXT_CSV = new ResourceType(ContentTypes.TEXT_CSV_UTF8, noCache(), true, singletonList("csv"));
    /**
     * <code>application/msword</code>
     */
    public static final ResourceType APPLICATION_MSWORD = new ResourceType(ContentTypes.APPLICATION_MSWORD, shortCache(), false, singletonList("doc"));
    /**
     * <code>application/vnd-ms-fontobject</code>
     */
    public static final ResourceType APPLICATION_VND_MS_FONTOBJECT = new ResourceType(ContentTypes.APPLICATION_VND_MS_FONTOBJECT, shortCache(), false, singletonList("eot"));
    /**
     * <code>application/epub-zip</code>
     */
    public static final ResourceType APPLICATION_EPUB_ZIP = new ResourceType(ContentTypes.APPLICATION_EPUB_ZIP, shortCache(), false, singletonList("epub"));
    /**
     * <code>application/gzip</code>
     */
    public static final ResourceType APPLICATION_GZIP = new ResourceType(ContentTypes.APPLICATION_GZIP, shortCache(), false, singletonList("gz"));
    /**
     * <code>image/gif</code>
     */
    public static final ResourceType IMAGE_GIF = new ResourceType(ContentTypes.IMAGE_GIF, shortCache(), false, singletonList("gif"));
    /**
     * <code>text/html;charset=utf-8</code>
     */
    public static final ResourceType TEXT_HTML = new ResourceType(ContentTypes.TEXT_HTML_UTF8, noCache(), true, asList("html", "htm"));
    /**
     * <code>image/x-icon</code>
     */
    public static final ResourceType IMAGE_X_ICON = new ResourceType(ContentTypes.IMAGE_X_ICON, shortCache(), false, singletonList("ico"));
    /**
     * <code>text/calendar;charset=utf-8</code>
     */
    public static final ResourceType TEXT_CALENDAR = new ResourceType(ContentTypes.TEXT_CALENDAR_UTF8, noCache(), true, singletonList("ics"));
    /**
     * <code>application/java-archive</code>
     */
    public static final ResourceType APPLICATION_JAVA_ARCHIVE = new ResourceType(ContentTypes.APPLICATION_JAVA_ARCHIVE, shortCache(), false, singletonList("jar"));
    /**
     * <code>image/jpeg</code>
     */
    public static final ResourceType IMAGE_JPEG = new ResourceType(ContentTypes.IMAGE_JPEG, shortCache(), false, asList("jpg", "jpeg"));

    /**
     * <code>application/javascript</code>
     */
    public static final ResourceType APPLICATION_JAVASCRIPT = new ResourceType(ContentTypes.APPLICATION_JAVASCRIPT,
        Headers.create()
            .add(HeaderNames.CACHE_CONTROL, "max-age=86400")
            .add(HeaderNames.X_CONTENT_TYPE_OPTIONS, HeaderValues.NOSNIFF),
        true, asList("js", "mjs"));
    /**
     * <code>application/json</code>
     */
    public static final ResourceType APPLICATION_JSON = new ResourceType(ContentTypes.APPLICATION_JSON, noCache(), true, singletonList("json"));
    /**
     * <code>web/app-manifest</code>
     */
    public static final ResourceType WEB_APP_MANIFEST = new ResourceType(ContentTypes.WEB_APP_MANIFEST, Headers.create()
        .add(HeaderNames.CACHE_CONTROL, "max-age=300"), true, singletonList("webmanifest"));
    /**
     * <code>audio/midi</code>
     */
    public static final ResourceType AUDIO_MIDI = new ResourceType(ContentTypes.AUDIO_MIDI, shortCache(), false, asList("mid", "midi"));
    /**
     * <code>video/mp4</code>
     */
    public static final ResourceType VIDEO_MP4 = new ResourceType(ContentTypes.VIDEO_MP4, shortCache(), false, singletonList("mp4"));
    /**
     * <code>video/mpeg</code>
     */
    public static final ResourceType VIDEO_MPEG = new ResourceType(ContentTypes.VIDEO_MPEG, shortCache(), false, singletonList("mpeg"));
    /**
     * <code>application/vnd-apple-installer-xml</code>
     */
    public static final ResourceType APPLICATION_VND_APPLE_INSTALLER_XML = new ResourceType(ContentTypes.APPLICATION_VND_APPLE_INSTALLER_XML, shortCache(), true, singletonList("mpkg"));
    /**
     * <code>application/vnd-oasis-opendocument-presentation</code>
     */
    public static final ResourceType APPLICATION_VND_OASIS_OPENDOCUMENT_PRESENTATION = new ResourceType(ContentTypes.APPLICATION_VND_OASIS_OPENDOCUMENT_PRESENTATION, shortCache(), false, singletonList("odp"));
    /**
     * <code>application/vnd-oasis-opendocument-spreadsheet</code>
     */
    public static final ResourceType APPLICATION_VND_OASIS_OPENDOCUMENT_SPREADSHEET = new ResourceType(ContentTypes.APPLICATION_VND_OASIS_OPENDOCUMENT_SPREADSHEET, shortCache(), false, singletonList("ods"));
    /**
     * <code>application/vnd-oasis-opendocument-text</code>
     */
    public static final ResourceType APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT = new ResourceType(ContentTypes.APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT, shortCache(), true, singletonList("odt"));
    /**
     * <code>audio/ogg</code>
     */
    public static final ResourceType AUDIO_OGG = new ResourceType(ContentTypes.AUDIO_OGG, shortCache(), false, singletonList("oga"));
    /**
     * <code>video/x-matroska</code>
     */
    public static final ResourceType VIDEO_X_MATROSKA = new ResourceType(ContentTypes.VIDEO_X_MATROSKA, shortCache(), false, singletonList("mkv"));
    /**
     * <code>video/ogg</code>
     */
    public static final ResourceType VIDEO_OGG = new ResourceType(ContentTypes.VIDEO_OGG, shortCache(), false, singletonList("ogv"));
    /**
     * <code>application/ogg</code>
     */
    public static final ResourceType APPLICATION_OGG = new ResourceType(ContentTypes.APPLICATION_OGG, shortCache(), false, singletonList("ogx"));
    /**
     * <code>font/otf</code>
     */
    public static final ResourceType FONT_OTF = new ResourceType(ContentTypes.FONT_OTF, shortCache(), true, singletonList("otf"));
    /**
     * <code>image/png</code>
     */
    public static final ResourceType IMAGE_PNG = new ResourceType(ContentTypes.IMAGE_PNG, shortCache(), false, singletonList("png"));
    /**
     * <code>application/pdf</code>
     */
    public static final ResourceType APPLICATION_PDF = new ResourceType(ContentTypes.APPLICATION_PDF, shortCache(), false, singletonList("pdf"));
    /**
     * <code>application/vnd-ms-powerpoint</code>
     */
    public static final ResourceType APPLICATION_VND_MS_POWERPOINT = new ResourceType(ContentTypes.APPLICATION_VND_MS_POWERPOINT, shortCache(), false, singletonList("ppt"));
    /**
     * <code>application/x-rar-compressed</code>
     */
    public static final ResourceType APPLICATION_X_RAR_COMPRESSED = new ResourceType(ContentTypes.APPLICATION_X_RAR_COMPRESSED, shortCache(), false, singletonList("rar"));
    /**
     * <code>application/rtf</code>
     */
    public static final ResourceType APPLICATION_RTF = new ResourceType(ContentTypes.APPLICATION_RTF, shortCache(), true, singletonList("rtf"));
    /**
     * <code>application/x-sh</code>
     */
    public static final ResourceType APPLICATION_X_SH = new ResourceType(ContentTypes.APPLICATION_X_SH, shortCache(), true, singletonList("sh"));
    /**
     * <code>image/svg-xml</code>
     */
    public static final ResourceType IMAGE_SVG_XML = new ResourceType(ContentTypes.IMAGE_SVG_XML, shortCache(), true, singletonList("svg"));
    /**
     * <code>application/x-shockwave-flash</code>
     */
    public static final ResourceType APPLICATION_X_SHOCKWAVE_FLASH = new ResourceType(ContentTypes.APPLICATION_X_SHOCKWAVE_FLASH, shortCache(), false, singletonList("swf"));
    /**
     * <code>application/x-tar</code>
     */
    public static final ResourceType APPLICATION_X_TAR = new ResourceType(ContentTypes.APPLICATION_X_TAR, shortCache(), false, singletonList("tar"));
    /**
     * <code>image/tiff</code>
     */
    public static final ResourceType IMAGE_TIFF = new ResourceType(ContentTypes.IMAGE_TIFF, shortCache(), false, asList("tiff", "tif"));
    /**
     * <code>application/typescript</code>
     */
    public static final ResourceType APPLICATION_TYPESCRIPT = new ResourceType(ContentTypes.APPLICATION_TYPESCRIPT, shortCache(), true, singletonList("ts"));
    /**
     * <code>font/ttf</code>
     */
    public static final ResourceType FONT_TTF = new ResourceType(ContentTypes.FONT_TTF, shortCache(), true, singletonList("ttf"));
    /**
     * <code>application/vnd-visio</code>
     */
    public static final ResourceType APPLICATION_VND_VISIO = new ResourceType(ContentTypes.APPLICATION_VND_VISIO, shortCache(), false, singletonList("vsd"));
    /**
     * <code>audio/x-wav</code>
     */
    public static final ResourceType AUDIO_X_WAV = new ResourceType(ContentTypes.AUDIO_X_WAV, shortCache(), false, singletonList("wav"));
    /**
     * <code>audio/webm</code>
     */
    public static final ResourceType AUDIO_WEBM = new ResourceType(ContentTypes.AUDIO_WEBM, shortCache(), false, singletonList("weba"));
    /**
     * <code>video/webm</code>
     */
    public static final ResourceType VIDEO_WEBM = new ResourceType(ContentTypes.VIDEO_WEBM, shortCache(), false, singletonList("webm"));
    /**
     * <code>image/avif</code>
     */
    public static final ResourceType IMAGE_AVIF = new ResourceType(ContentTypes.IMAGE_AVIF, shortCache(), false, singletonList("avif"));
    /**
     * <code>image/webp</code>
     */
    public static final ResourceType IMAGE_WEBP = new ResourceType(ContentTypes.IMAGE_WEBP, shortCache(), false, singletonList("webp"));
    /**
     * <code>font/woff</code>
     */
    public static final ResourceType FONT_WOFF = new ResourceType(ContentTypes.FONT_WOFF, shortCache(), false, singletonList("woff"));
    /**
     * <code>font/woff2</code>
     */
    public static final ResourceType FONT_WOFF2 = new ResourceType(ContentTypes.FONT_WOFF2, shortCache(), false, singletonList("woff2"));
    /**
     * <code>application/xhtml-xml</code>
     */
    public static final ResourceType APPLICATION_XHTML_XML = new ResourceType(ContentTypes.APPLICATION_XHTML_XML, shortCache(), true, singletonList("xhtml"));
    /**
     * <code>application/vnd-ms-excel</code>
     */
    public static final ResourceType APPLICATION_VND_MS_EXCEL = new ResourceType(ContentTypes.APPLICATION_VND_MS_EXCEL, shortCache(), false, asList("xls", "xlsx"));
    /**
     * <code>application/xml</code>
     */
    public static final ResourceType APPLICATION_XML = new ResourceType(ContentTypes.APPLICATION_XML, shortCache(), true, singletonList("xml"));
    /**
     * <code>application/vnd-mozilla-xul-xml</code>
     */
    public static final ResourceType APPLICATION_VND_MOZILLA_XUL_XML = new ResourceType(ContentTypes.APPLICATION_VND_MOZILLA_XUL_XML, shortCache(), true, singletonList("xul"));
    /**
     * <code>application/zip</code>
     */
    public static final ResourceType APPLICATION_ZIP = new ResourceType(ContentTypes.APPLICATION_ZIP, shortCache(), false, singletonList("zip"));
    /**
     * <code>video/3gpp</code>
     */
    public static final ResourceType VIDEO_3GPP = new ResourceType(ContentTypes.VIDEO_3GPP, shortCache(), false, singletonList("3gp"));
    /**
     * <code>video/3gpp2</code>
     */
    public static final ResourceType VIDEO_3GPP2 = new ResourceType(ContentTypes.VIDEO_3GPP2, shortCache(), false, singletonList("3g2"));
    /**
     * <code>application/x-7z-compressed</code>
     */
    public static final ResourceType APPLICATION_X_7Z_COMPRESSED = new ResourceType(ContentTypes.APPLICATION_X_7Z_COMPRESSED, shortCache(), false, singletonList("7z"));


    static {
        DEFAULT_EXTENSION_MAPPINGS = Collections.unmodifiableMap(getDefaultMapping());
    }

    /**
     * Can be used as a base to customise mime types via {@link ResourceHandlerBuilder#withExtensionToResourceType(Map)}
     *
     * @return Returns the built-in mapping of file extension to resource type that can be added to in order to customise resource types.
     */
    public static HashMap<String, ResourceType> getDefaultMapping() {
        HashMap<String, ResourceType> map = new HashMap<>();
        for (ResourceType rt : getResourceTypes()) {
            for (String extension : rt.extensions()) {
                map.put(extension, rt);
            }
        }
        return map;
    }

    /**
     * Given a list of resources, returns the ones that are gzippable
     * @param resourceTypes Types to check
     * @return The types that have {@link #gzip()} returning <code>true</code>
     */
    public static Set<String> gzippableMimeTypes(Collection<ResourceType> resourceTypes) {
        return resourceTypes.stream().filter(ResourceType::gzip).map(rt -> {
            String s = rt.mimeType().toString();
            int i = s.indexOf(";");
            if (i > -1) {
                s = s.substring(0, i);
            }
            return s;
        }).collect(Collectors.toSet());
    }

    /**
     * @return All the resource types defined by mu-server
     */
    public static List<ResourceType> getResourceTypes() {
        List<ResourceType> all = new ArrayList<>();
        for (Field field : ResourceType.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            int modifiers = field.getModifiers();
            if (type.equals(ResourceType.class) && Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers)) {
                field.setAccessible(true);
                try {
                    ResourceType rt = (ResourceType) field.get(null);
                    all.add(rt);
                } catch (IllegalAccessException e) {
                    throw new MuException("Error getting resource types for " + field, e);
                }
            }
        }
        return all;
    }

    @Override
    public String toString() {
        return "ResourceType{" +
            "mimeType=" + mimeType() +
            ", headers=" + headers() +
            ", extensions=" + extensions() +
            ", gzip=" + gzip() +
            '}';
    }

}
