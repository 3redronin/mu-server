package io.muserver.handlers;

import io.muserver.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

/**
 * <p>Used by the {@link ResourceHandler} to specify a mapping between file name extensions and mime types, and whether they should be gzipped or not.</p>
 * <p>A number of common mappings are provided by default. To specify your custom settings, use {@link ResourceHandlerBuilder#withExtensionToResourceType(Map)}</p>
 */
public class ResourceType {
    public static final Map<String, ResourceType> DEFAULT_EXTENSION_MAPPINGS;

    public final CharSequence mimeType;
    public final Headers headers;
    public final List<String> extensions;
    public final boolean gzip;

    public ResourceType(CharSequence mimeType, Headers headers, boolean gzip, List<String> extensions) {
        this.mimeType = mimeType;
        this.headers = headers;
        this.gzip = gzip;
        this.extensions = extensions;
    }

    public static final Headers shortCache() {
        return new Headers()
            .add(HeaderNames.CACHE_CONTROL, "max-age=300");
    }

    public static final Headers noCache() {
        return new Headers()
            .add(HeaderNames.CACHE_CONTROL, HeaderValues.NO_CACHE);
    }

    public static final ResourceType DEFAULT = new ResourceType(ContentTypes.APPLICATION_OCTET_STREAM, shortCache(), false, Collections.emptyList());

    public static final ResourceType AUDIO_AAC = new ResourceType(ContentTypes.AUDIO_AAC, shortCache(), false, asList("aac"));
    public static final ResourceType APPLICATION_X_ABIWORD = new ResourceType(ContentTypes.APPLICATION_X_ABIWORD, shortCache(), false, asList("abw"));
    public static final ResourceType VIDEO_X_MSVIDEO = new ResourceType(ContentTypes.VIDEO_X_MSVIDEO, shortCache(), false, asList("avi"));
    public static final ResourceType APPLICATION_VND_AMAZON_EBOOK = new ResourceType(ContentTypes.APPLICATION_VND_AMAZON_EBOOK, shortCache(), false, asList("azw"));
    public static final ResourceType APPLICATION_OCTET_STREAM = new ResourceType(ContentTypes.APPLICATION_OCTET_STREAM, shortCache(), false, asList("bin", "arc"));
    public static final ResourceType APPLICATION_X_BZIP = new ResourceType(ContentTypes.APPLICATION_X_BZIP, shortCache(), false, asList("bz"));
    public static final ResourceType APPLICATION_X_BZIP2 = new ResourceType(ContentTypes.APPLICATION_X_BZIP2, shortCache(), false, asList("bz2"));
    public static final ResourceType APPLICATION_X_CSH = new ResourceType(ContentTypes.APPLICATION_X_CSH, shortCache(), false, asList("csh"));
    public static final ResourceType TEXT_CSS = new ResourceType(ContentTypes.TEXT_CSS,
        new Headers()
            .add(HeaderNames.CACHE_CONTROL, "max-age=300")
            .add(HeaderNames.X_CONTENT_TYPE_OPTIONS, HeaderValues.NOSNIFF),
        true, asList("css"));
    public static final ResourceType TEXT_PLAIN = new ResourceType(ContentTypes.TEXT_PLAIN, noCache(), true, asList("txt", "ini", "gitignore", "gitattributes", "cfg"));
    public static final ResourceType TEXT_CSV = new ResourceType(ContentTypes.TEXT_CSV, noCache(), true, asList("csv"));
    public static final ResourceType APPLICATION_MSWORD = new ResourceType(ContentTypes.APPLICATION_MSWORD, shortCache(), false, asList("doc"));
    public static final ResourceType APPLICATION_VND_MS_FONTOBJECT = new ResourceType(ContentTypes.APPLICATION_VND_MS_FONTOBJECT, shortCache(), false, asList("eot"));
    public static final ResourceType APPLICATION_EPUB_ZIP = new ResourceType(ContentTypes.APPLICATION_EPUB_ZIP, shortCache(), false, asList("epub"));
    public static final ResourceType IMAGE_GIF = new ResourceType(ContentTypes.IMAGE_GIF, shortCache(), false, asList("gif"));
    public static final ResourceType TEXT_HTML = new ResourceType(ContentTypes.TEXT_HTML, noCache(), true, asList("html", "htm"));
    public static final ResourceType IMAGE_X_ICON = new ResourceType(ContentTypes.IMAGE_X_ICON, shortCache(), false, asList("ico"));
    public static final ResourceType TEXT_CALENDAR = new ResourceType(ContentTypes.TEXT_CALENDAR, noCache(), true, asList("ics"));
    public static final ResourceType APPLICATION_JAVA_ARCHIVE = new ResourceType(ContentTypes.APPLICATION_JAVA_ARCHIVE, shortCache(), false, asList("jar"));
    public static final ResourceType IMAGE_JPEG = new ResourceType(ContentTypes.IMAGE_JPEG, shortCache(), false, asList("jpg", "jpeg"));
    public static final ResourceType TEXT_JAVASCRIPT = new ResourceType(ContentTypes.APPLICATION_JAVASCRIPT,
        new Headers()
            .add(HeaderNames.CACHE_CONTROL, "max-age=300")
            .add(HeaderNames.X_CONTENT_TYPE_OPTIONS, HeaderValues.NOSNIFF),
        true, asList("js"));
    public static final ResourceType APPLICATION_JSON = new ResourceType(ContentTypes.APPLICATION_JSON, noCache(), true, asList("json"));
    public static final ResourceType AUDIO_MIDI = new ResourceType(ContentTypes.AUDIO_MIDI, shortCache(), false, asList("mid", "midi"));
    public static final ResourceType VIDEO_MPEG = new ResourceType(ContentTypes.VIDEO_MPEG, shortCache(), false, asList("mpeg"));
    public static final ResourceType APPLICATION_VND_APPLE_INSTALLER_XML = new ResourceType(ContentTypes.APPLICATION_VND_APPLE_INSTALLER_XML, shortCache(), true, asList("mpkg"));
    public static final ResourceType APPLICATION_VND_OASIS_OPENDOCUMENT_PRESENTATION = new ResourceType(ContentTypes.APPLICATION_VND_OASIS_OPENDOCUMENT_PRESENTATION, shortCache(), false, asList("odp"));
    public static final ResourceType APPLICATION_VND_OASIS_OPENDOCUMENT_SPREADSHEET = new ResourceType(ContentTypes.APPLICATION_VND_OASIS_OPENDOCUMENT_SPREADSHEET, shortCache(), false, asList("ods"));
    public static final ResourceType APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT = new ResourceType(ContentTypes.APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT, shortCache(), true, asList("odt"));
    public static final ResourceType AUDIO_OGG = new ResourceType(ContentTypes.AUDIO_OGG, shortCache(), false, asList("oga"));
    public static final ResourceType VIDEO_X_MATROSKA = new ResourceType(ContentTypes.VIDEO_X_MATROSKA, shortCache(), false, asList("mkv"));
    public static final ResourceType VIDEO_OGG = new ResourceType(ContentTypes.VIDEO_OGG, shortCache(), false, asList("ogv"));
    public static final ResourceType APPLICATION_OGG = new ResourceType(ContentTypes.APPLICATION_OGG, shortCache(), false, asList("ogx"));
    public static final ResourceType FONT_OTF = new ResourceType(ContentTypes.FONT_OTF, shortCache(), true, asList("otf"));
    public static final ResourceType IMAGE_PNG = new ResourceType(ContentTypes.IMAGE_PNG, shortCache(), false, asList("png"));
    public static final ResourceType APPLICATION_PDF = new ResourceType(ContentTypes.APPLICATION_PDF, shortCache(), false, asList("pdf"));
    public static final ResourceType APPLICATION_VND_MS_POWERPOINT = new ResourceType(ContentTypes.APPLICATION_VND_MS_POWERPOINT, shortCache(), false, asList("ppt"));
    public static final ResourceType APPLICATION_X_RAR_COMPRESSED = new ResourceType(ContentTypes.APPLICATION_X_RAR_COMPRESSED, shortCache(), false, asList("rar"));
    public static final ResourceType APPLICATION_RTF = new ResourceType(ContentTypes.APPLICATION_RTF, shortCache(), true, asList("rtf"));
    public static final ResourceType APPLICATION_X_SH = new ResourceType(ContentTypes.APPLICATION_X_SH, shortCache(), true, asList("sh"));
    public static final ResourceType IMAGE_SVG_XML = new ResourceType(ContentTypes.IMAGE_SVG_XML, shortCache(), true, asList("svg"));
    public static final ResourceType APPLICATION_X_SHOCKWAVE_FLASH = new ResourceType(ContentTypes.APPLICATION_X_SHOCKWAVE_FLASH, shortCache(), false, asList("swf"));
    public static final ResourceType APPLICATION_X_TAR = new ResourceType(ContentTypes.APPLICATION_X_TAR, shortCache(), false, asList("tar"));
    public static final ResourceType IMAGE_TIFF = new ResourceType(ContentTypes.IMAGE_TIFF, shortCache(), false, asList("tiff", "tif"));
    public static final ResourceType APPLICATION_TYPESCRIPT = new ResourceType(ContentTypes.APPLICATION_TYPESCRIPT, shortCache(), true, asList("ts"));
    public static final ResourceType FONT_TTF = new ResourceType(ContentTypes.FONT_TTF, shortCache(), true, asList("ttf"));
    public static final ResourceType APPLICATION_VND_VISIO = new ResourceType(ContentTypes.APPLICATION_VND_VISIO, shortCache(), false, asList("vsd"));
    public static final ResourceType AUDIO_X_WAV = new ResourceType(ContentTypes.AUDIO_X_WAV, shortCache(), false, asList("wav"));
    public static final ResourceType AUDIO_WEBM = new ResourceType(ContentTypes.AUDIO_WEBM, shortCache(), false, asList("weba"));
    public static final ResourceType VIDEO_WEBM = new ResourceType(ContentTypes.VIDEO_WEBM, shortCache(), false, asList("webm"));
    public static final ResourceType IMAGE_WEBP = new ResourceType(ContentTypes.IMAGE_WEBP, shortCache(), false, asList("webp"));
    public static final ResourceType FONT_WOFF = new ResourceType(ContentTypes.FONT_WOFF, shortCache(), false, asList("woff"));
    public static final ResourceType FONT_WOFF2 = new ResourceType(ContentTypes.FONT_WOFF2, shortCache(), false, asList("woff2"));
    public static final ResourceType APPLICATION_XHTML_XML = new ResourceType(ContentTypes.APPLICATION_XHTML_XML, shortCache(), true, asList("xhtml"));
    public static final ResourceType APPLICATION_VND_MS_EXCEL = new ResourceType(ContentTypes.APPLICATION_VND_MS_EXCEL, shortCache(), false, asList("xls", "xlsx"));
    public static final ResourceType APPLICATION_XML = new ResourceType(ContentTypes.APPLICATION_XML, shortCache(), true, asList("xml"));
    public static final ResourceType APPLICATION_VND_MOZILLA_XUL_XML = new ResourceType(ContentTypes.APPLICATION_VND_MOZILLA_XUL_XML, shortCache(), true, asList("xul"));
    public static final ResourceType APPLICATION_ZIP = new ResourceType(ContentTypes.APPLICATION_ZIP, shortCache(), false, asList("zip"));
    public static final ResourceType VIDEO_3GPP = new ResourceType(ContentTypes.VIDEO_3GPP, shortCache(), false, asList("3gp"));
    public static final ResourceType VIDEO_3GPP2 = new ResourceType(ContentTypes.VIDEO_3GPP2, shortCache(), false, asList("3g2"));
    public static final ResourceType APPLICATION_X_7Z_COMPRESSED = new ResourceType(ContentTypes.APPLICATION_X_7Z_COMPRESSED, shortCache(), false, asList("7z"));


    static {
        HashMap<String, ResourceType> map = new HashMap<>();
        for (ResourceType rt : getResourceTypes()) {
            for (String extension : rt.extensions) {
                map.put(extension, rt);
            }
        }
        DEFAULT_EXTENSION_MAPPINGS = Collections.unmodifiableMap(map);
    }

    public static Set<String> gzippableMimeTypes(List<ResourceType> resourceTypes) {
        return resourceTypes.stream().filter(rt -> rt.gzip).map(rt -> rt.mimeType.toString()).collect(Collectors.toSet());
    }

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
            "mimeType=" + mimeType +
            ", headers=" + headers +
            ", extensions=" + extensions +
            ", gzip=" + gzip +
            '}';
    }
}
