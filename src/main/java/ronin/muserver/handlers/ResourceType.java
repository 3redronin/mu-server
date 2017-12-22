package ronin.muserver.handlers;

import ronin.muserver.ContentTypes;
import ronin.muserver.Headers;
import ronin.muserver.MuException;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

public class ResourceType {
    public static final Map<String,ResourceType> DEFAULT_EXTENSION_MAPPINGS;

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

    public static final Headers DEFAULT_HEADERS = new Headers();

    public static final ResourceType DEFAULT = new ResourceType(ContentTypes.APPLICATION_OCTET_STREAM, DEFAULT_HEADERS, false, Collections.emptyList());

    public static final ResourceType AUDIO_AAC = new ResourceType(ContentTypes.AUDIO_AAC, DEFAULT_HEADERS, false, asList("aac"));
    public static final ResourceType APPLICATION_X_ABIWORD = new ResourceType(ContentTypes.APPLICATION_X_ABIWORD, DEFAULT_HEADERS, false, asList("abw"));
    public static final ResourceType VIDEO_X_MSVIDEO = new ResourceType(ContentTypes.VIDEO_X_MSVIDEO, DEFAULT_HEADERS, false, asList("avi"));
    public static final ResourceType APPLICATION_VND_AMAZON_EBOOK = new ResourceType(ContentTypes.APPLICATION_VND_AMAZON_EBOOK, DEFAULT_HEADERS, false, asList("azw"));
    public static final ResourceType APPLICATION_OCTET_STREAM = new ResourceType(ContentTypes.APPLICATION_OCTET_STREAM, DEFAULT_HEADERS, false, asList("bin", "arc"));
    public static final ResourceType APPLICATION_X_BZIP = new ResourceType(ContentTypes.APPLICATION_X_BZIP, DEFAULT_HEADERS, false, asList("bz"));
    public static final ResourceType APPLICATION_X_BZIP2 = new ResourceType(ContentTypes.APPLICATION_X_BZIP2, DEFAULT_HEADERS, false, asList("bz2"));
    public static final ResourceType APPLICATION_X_CSH = new ResourceType(ContentTypes.APPLICATION_X_CSH, DEFAULT_HEADERS, false, asList("csh"));
    public static final ResourceType TEXT_CSS = new ResourceType(ContentTypes.TEXT_CSS, DEFAULT_HEADERS, true, asList("css"));
    public static final ResourceType TEXT_CSV = new ResourceType(ContentTypes.TEXT_CSV, DEFAULT_HEADERS, true, asList("csv"));
    public static final ResourceType APPLICATION_MSWORD = new ResourceType(ContentTypes.APPLICATION_MSWORD, DEFAULT_HEADERS, false, asList("doc"));
    public static final ResourceType APPLICATION_VND_MS_FONTOBJECT = new ResourceType(ContentTypes.APPLICATION_VND_MS_FONTOBJECT, DEFAULT_HEADERS, false, asList("eot"));
    public static final ResourceType APPLICATION_EPUB_ZIP = new ResourceType(ContentTypes.APPLICATION_EPUB_ZIP, DEFAULT_HEADERS, false, asList("epub"));
    public static final ResourceType IMAGE_GIF = new ResourceType(ContentTypes.IMAGE_GIF, DEFAULT_HEADERS, false, asList("gif"));
    public static final ResourceType TEXT_HTML = new ResourceType(ContentTypes.TEXT_HTML, DEFAULT_HEADERS, true, asList("html", "htm"));
    public static final ResourceType IMAGE_X_ICON = new ResourceType(ContentTypes.IMAGE_X_ICON, DEFAULT_HEADERS, false, asList("ico"));
    public static final ResourceType TEXT_CALENDAR = new ResourceType(ContentTypes.TEXT_CALENDAR, DEFAULT_HEADERS, true, asList("ics"));
    public static final ResourceType APPLICATION_JAVA_ARCHIVE = new ResourceType(ContentTypes.APPLICATION_JAVA_ARCHIVE, DEFAULT_HEADERS, false, asList("jar"));
    public static final ResourceType IMAGE_JPEG = new ResourceType(ContentTypes.IMAGE_JPEG, DEFAULT_HEADERS, false, asList("jpg", "jpeg"));
    public static final ResourceType APPLICATION_JAVASCRIPT = new ResourceType(ContentTypes.APPLICATION_JAVASCRIPT, DEFAULT_HEADERS, true, asList("js"));
    public static final ResourceType APPLICATION_JSON = new ResourceType(ContentTypes.APPLICATION_JSON, DEFAULT_HEADERS, true, asList("json"));
    public static final ResourceType AUDIO_MIDI = new ResourceType(ContentTypes.AUDIO_MIDI, DEFAULT_HEADERS, false, asList("mid", "midi"));
    public static final ResourceType VIDEO_MPEG = new ResourceType(ContentTypes.VIDEO_MPEG, DEFAULT_HEADERS, false, asList("mpeg"));
    public static final ResourceType APPLICATION_VND_APPLE_INSTALLER_XML = new ResourceType(ContentTypes.APPLICATION_VND_APPLE_INSTALLER_XML, DEFAULT_HEADERS, true, asList("mpkg"));
    public static final ResourceType APPLICATION_VND_OASIS_OPENDOCUMENT_PRESENTATION = new ResourceType(ContentTypes.APPLICATION_VND_OASIS_OPENDOCUMENT_PRESENTATION, DEFAULT_HEADERS, false, asList("odp"));
    public static final ResourceType APPLICATION_VND_OASIS_OPENDOCUMENT_SPREADSHEET = new ResourceType(ContentTypes.APPLICATION_VND_OASIS_OPENDOCUMENT_SPREADSHEET, DEFAULT_HEADERS, false, asList("ods"));
    public static final ResourceType APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT = new ResourceType(ContentTypes.APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT, DEFAULT_HEADERS, true, asList("odt"));
    public static final ResourceType AUDIO_OGG = new ResourceType(ContentTypes.AUDIO_OGG, DEFAULT_HEADERS, false, asList("oga"));
    public static final ResourceType VIDEO_OGG = new ResourceType(ContentTypes.VIDEO_OGG, DEFAULT_HEADERS, false, asList("ogv"));
    public static final ResourceType APPLICATION_OGG = new ResourceType(ContentTypes.APPLICATION_OGG, DEFAULT_HEADERS, false, asList("ogx"));
    public static final ResourceType FONT_OTF = new ResourceType(ContentTypes.FONT_OTF, DEFAULT_HEADERS, true, asList("otf"));
    public static final ResourceType IMAGE_PNG = new ResourceType(ContentTypes.IMAGE_PNG, DEFAULT_HEADERS, false, asList("png"));
    public static final ResourceType APPLICATION_PDF = new ResourceType(ContentTypes.APPLICATION_PDF, DEFAULT_HEADERS, false, asList("pdf"));
    public static final ResourceType APPLICATION_VND_MS_POWERPOINT = new ResourceType(ContentTypes.APPLICATION_VND_MS_POWERPOINT, DEFAULT_HEADERS, false, asList("ppt"));
    public static final ResourceType APPLICATION_X_RAR_COMPRESSED = new ResourceType(ContentTypes.APPLICATION_X_RAR_COMPRESSED, DEFAULT_HEADERS, false, asList("rar"));
    public static final ResourceType APPLICATION_RTF = new ResourceType(ContentTypes.APPLICATION_RTF, DEFAULT_HEADERS, true, asList("rtf"));
    public static final ResourceType APPLICATION_X_SH = new ResourceType(ContentTypes.APPLICATION_X_SH, DEFAULT_HEADERS, true, asList("sh"));
    public static final ResourceType IMAGE_SVG_XML = new ResourceType(ContentTypes.IMAGE_SVG_XML, DEFAULT_HEADERS, true, asList("svg"));
    public static final ResourceType APPLICATION_X_SHOCKWAVE_FLASH = new ResourceType(ContentTypes.APPLICATION_X_SHOCKWAVE_FLASH, DEFAULT_HEADERS, false, asList("swf"));
    public static final ResourceType APPLICATION_X_TAR = new ResourceType(ContentTypes.APPLICATION_X_TAR, DEFAULT_HEADERS, false, asList("tar"));
    public static final ResourceType IMAGE_TIFF = new ResourceType(ContentTypes.IMAGE_TIFF, DEFAULT_HEADERS, false, asList("tiff", "tif"));
    public static final ResourceType APPLICATION_TYPESCRIPT = new ResourceType(ContentTypes.APPLICATION_TYPESCRIPT, DEFAULT_HEADERS, true, asList("ts"));
    public static final ResourceType FONT_TTF = new ResourceType(ContentTypes.FONT_TTF, DEFAULT_HEADERS, true, asList("ttf"));
    public static final ResourceType APPLICATION_VND_VISIO = new ResourceType(ContentTypes.APPLICATION_VND_VISIO, DEFAULT_HEADERS, false, asList("vsd"));
    public static final ResourceType AUDIO_X_WAV = new ResourceType(ContentTypes.AUDIO_X_WAV, DEFAULT_HEADERS, false, asList("wav"));
    public static final ResourceType AUDIO_WEBM = new ResourceType(ContentTypes.AUDIO_WEBM, DEFAULT_HEADERS, false, asList("weba"));
    public static final ResourceType VIDEO_WEBM = new ResourceType(ContentTypes.VIDEO_WEBM, DEFAULT_HEADERS, false, asList("webm"));
    public static final ResourceType IMAGE_WEBP = new ResourceType(ContentTypes.IMAGE_WEBP, DEFAULT_HEADERS, false, asList("webp"));
    public static final ResourceType FONT_WOFF = new ResourceType(ContentTypes.FONT_WOFF, DEFAULT_HEADERS, false, asList("woff"));
    public static final ResourceType FONT_WOFF2 = new ResourceType(ContentTypes.FONT_WOFF2, DEFAULT_HEADERS, false, asList("woff2"));
    public static final ResourceType APPLICATION_XHTML_XML = new ResourceType(ContentTypes.APPLICATION_XHTML_XML, DEFAULT_HEADERS, true, asList("xhtml"));
    public static final ResourceType APPLICATION_VND_MS_EXCEL = new ResourceType(ContentTypes.APPLICATION_VND_MS_EXCEL, DEFAULT_HEADERS, false, asList("xls", "xlsx"));
    public static final ResourceType APPLICATION_XML = new ResourceType(ContentTypes.APPLICATION_XML, DEFAULT_HEADERS, true, asList("xml"));
    public static final ResourceType APPLICATION_VND_MOZILLA_XUL_XML = new ResourceType(ContentTypes.APPLICATION_VND_MOZILLA_XUL_XML, DEFAULT_HEADERS, true, asList("xul"));
    public static final ResourceType APPLICATION_ZIP = new ResourceType(ContentTypes.APPLICATION_ZIP, DEFAULT_HEADERS, false, asList("zip"));
    public static final ResourceType VIDEO_3GPP = new ResourceType(ContentTypes.VIDEO_3GPP, DEFAULT_HEADERS, false, asList("3gp"));
    public static final ResourceType VIDEO_3GPP2 = new ResourceType(ContentTypes.VIDEO_3GPP2, DEFAULT_HEADERS, false, asList("3g2"));
    public static final ResourceType APPLICATION_X_7Z_COMPRESSED = new ResourceType(ContentTypes.APPLICATION_X_7Z_COMPRESSED, DEFAULT_HEADERS, false, asList("7z"));


    static {
        HashMap<String, ResourceType> map = new HashMap<>();
        for (ResourceType rt : getResourceTypes()) {
            for (String extension : rt.extensions) {
                map.put(extension, rt);
            }
        }
        DEFAULT_EXTENSION_MAPPINGS = Collections.unmodifiableMap(map);
    }

    public static Set<CharSequence> gzippableMimeTypes(List<ResourceType> resourceTypes) {
        return resourceTypes.stream().filter(rt -> rt.gzip).map(rt -> rt.mimeType).collect(Collectors.toSet());
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
