package io.muserver.rest;

import io.muserver.Mutils;
import io.muserver.ParameterizedHeader;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class CacheControlHeaderDelegate implements RuntimeDelegate.HeaderDelegate<CacheControl> {
    @Override
    public CacheControl fromString(String value) {
        Mutils.notNull("value", value);
        CacheControl result = new CacheControl();
        result.setPrivate(false);
        result.setNoCache(false);
        result.setNoStore(false);
        result.setNoTransform(false);
        result.setMustRevalidate(false);
        result.setProxyRevalidate(false);

        for (Map.Entry<String, @Nullable String> directive : ParameterizedHeader.fromString(value).parameters().entrySet()) {
            String name = directive.getKey();
            @Nullable String directiveValue = directive.getValue();
            switch (name.toLowerCase(Locale.ROOT)) {
                case "private":
                    result.setPrivate(true);
                    addFieldNames(result.getPrivateFields(), directiveValue);
                    break;
                case "no-cache":
                    result.setNoCache(true);
                    addFieldNames(result.getNoCacheFields(), directiveValue);
                    break;
                case "no-store":
                    result.setNoStore(true);
                    break;
                case "no-transform":
                    result.setNoTransform(true);
                    break;
                case "must-revalidate":
                    result.setMustRevalidate(true);
                    break;
                case "proxy-revalidate":
                    result.setProxyRevalidate(true);
                    break;
                case "max-age":
                    result.setMaxAge(Integer.parseInt(directiveValue));
                    break;
                case "s-maxage":
                    result.setSMaxAge(Integer.parseInt(directiveValue));
                    break;
                default:
                    result.getCacheExtension().put(name, directiveValue);
                    break;
            }
        }
        return result;
    }

    @Override
    public String toString(CacheControl value) {
        Mutils.notNull("value", value);
        List<String> directives = new ArrayList<>();
        if (value.isPrivate()) {
            addFieldDirective(directives, "private", value.getPrivateFields());
        }
        if (value.isNoCache()) {
            addFieldDirective(directives, "no-cache", value.getNoCacheFields());
        }
        if (value.isNoStore()) {
            directives.add("no-store");
        }
        if (value.isNoTransform()) {
            directives.add("no-transform");
        }
        if (value.isMustRevalidate()) {
            directives.add("must-revalidate");
        }
        if (value.isProxyRevalidate()) {
            directives.add("proxy-revalidate");
        }
        if (value.getMaxAge() != -1) {
            directives.add("max-age=" + value.getMaxAge());
        }
        if (value.getSMaxAge() != -1) {
            directives.add("s-maxage=" + value.getSMaxAge());
        }
        for (Map.Entry<String, String> extension : value.getCacheExtension().entrySet()) {
            String extensionValue = extension.getValue();
            directives.add(extensionValue == null || extensionValue.isEmpty()
                ? extension.getKey()
                : extension.getKey() + "=" + quoteIfNeeded(extensionValue));
        }
        return String.join(", ", directives);
    }

    private static void addFieldNames(List<String> fields, @Nullable String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        for (String field : value.split(",")) {
            String trimmed = field.trim();
            if (!trimmed.isEmpty()) {
                fields.add(trimmed);
            }
        }
    }

    private static void addFieldDirective(List<String> directives, String name, List<String> fields) {
        if (fields.isEmpty()) {
            directives.add(name);
        } else {
            directives.add(name + "=\"" + escapeQuoted(String.join(", ", fields)) + "\"");
        }
    }

    private static String quoteIfNeeded(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!isTokenCharacter(value.charAt(i))) {
                return "\"" + escapeQuoted(value) + "\"";
            }
        }
        return value;
    }

    private static String escapeQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean isTokenCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
            || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }
}
