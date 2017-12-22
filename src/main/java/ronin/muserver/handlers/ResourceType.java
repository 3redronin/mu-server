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

    public static final ResourceType DEFAULT = new ResourceType(ContentTypes.TEXT_PLAIN, DEFAULT_HEADERS, true, Collections.emptyList());
    public static final ResourceType HTML = new ResourceType(ContentTypes.TEXT_HTML, DEFAULT_HEADERS, true, asList("html", "htm"));
    public static final ResourceType PLAIN_TEXT = new ResourceType(ContentTypes.TEXT_PLAIN, DEFAULT_HEADERS, true, asList("txt"));
    public static final ResourceType CSS = new ResourceType(ContentTypes.TEXT_CSS, DEFAULT_HEADERS, true, asList("css"));
    public static final ResourceType JAVASCRIPT = new ResourceType(ContentTypes.APPLICATION_JAVASCRIPT, DEFAULT_HEADERS, true, asList("js"));
    public static final ResourceType JSON = new ResourceType(ContentTypes.APPLICATION_JSON, DEFAULT_HEADERS, true, asList("json"));
    public static final ResourceType JPEG = new ResourceType(ContentTypes.IMAGE_JPEG, DEFAULT_HEADERS, false, asList("jpg", "jpeg"));
    public static final ResourceType XML = new ResourceType(ContentTypes.TEXT_XML, DEFAULT_HEADERS, true, asList("xml"));
    public static final ResourceType CSV = new ResourceType(ContentTypes.TEXT_CSV, DEFAULT_HEADERS, true, asList("csv"));
    public static final ResourceType MARKDOWN = new ResourceType(ContentTypes.TEXT_MARKDOWN, DEFAULT_HEADERS, true, asList("md"));
    public static final ResourceType SVG = new ResourceType(ContentTypes.IMAGE_SVG, DEFAULT_HEADERS, true, asList("svg"));

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
