package io.muserver.rest;

import io.muserver.HeaderNames;
import io.muserver.Method;
import io.muserver.MuResponse;
import jakarta.ws.rs.core.MediaType;
import org.jspecify.annotations.Nullable;
import java.util.*;

/** RFC 10008 media ranges serialized as an RFC 9651 List, not an Accept field. */
final class AcceptQueryHeader {
    static void write(MuResponse response, Set<RequestMatcher.MatchedMethod> candidates) {
        if (response.headers().contains(HeaderNames.ACCEPT_QUERY)) return;
        List<MediaType> types = new ArrayList<>();
        for (RequestMatcher.MatchedMethod candidate : candidates) {
            if (candidate.resourceMethod.httpMethod() == Method.QUERY) {
                types.addAll(candidate.resourceMethod.effectiveConsumes);
            }
        }
        String value = format(types);
        if (value != null) response.headers().set(HeaderNames.ACCEPT_QUERY, value);
    }

    static @Nullable String format(Collection<MediaType> types) {
        Set<String> items = new TreeSet<>();
        for (MediaType type : types) {
            String major = type.getType().toLowerCase(Locale.ROOT);
            String minor = type.getSubtype().toLowerCase(Locale.ROOT);
            if (!token(major) || !token(minor)
                || (major.contains("*") && !major.equals("*"))
                || (minor.contains("*") && !minor.equals("*"))
                || (major.equals("*") && !minor.equals("*"))) return null;
            StringBuilder item = new StringBuilder(quote(major + "/" + minor));
            for (Map.Entry<String, String> parameter : new TreeMap<>(type.getParameters()).entrySet()) {
                String key = parameter.getKey().toLowerCase(Locale.ROOT);
                String value = parameter.getValue();
                if (!key.matches("[a-z*][a-z0-9_.*-]*") || !value.chars().allMatch(c -> c >= 0x20 && c <= 0x7e)) return null;
                item.append(';').append(key).append('=').append(quote(value));
            }
            items.add(item.toString());
        }
        return items.isEmpty() ? null : String.join(", ", items);
    }

    private static boolean token(String value) {
        return value.matches("[!#$%&'*+.^_`|~0-9a-z-]+");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
