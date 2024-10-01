package io.muserver;

import java.util.*;

import static io.muserver.Mutils.urlDecode;
import static io.muserver.Mutils.urlEncode;

/**
 * A query string
 */
public class QueryString implements RequestParameters {

    private final Map<String, List<String>> map;

    QueryString(Map<String, List<String>> map) {
        this.map = map;
    }

    @Override
    public Map<String, List<String>> all() {
        return map;
    }

    /**
     * Parses a query string into an object
     * @param input A query string value, without '?'
     * @return a parsed QueryString object
     */
    public static QueryString parse(String input) {
        if (input == null || input.isEmpty()) return EMPTY;
        if (input.charAt(0) == '?') {
            input = input.substring(1);
        }
        if (input.isEmpty()) return EMPTY;
        var map = new LinkedHashMap<String, List<String>>(); // to keep the order
        String[] pairs = input.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            String key = urlDecode(keyValue[0]);
            if (!key.isEmpty()) {
                String value;
                if (keyValue.length == 2) {
                    value = urlDecode(keyValue[1]);
                } else {
                    value = "";
                }
                map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            }
        }
        return new QueryString(map);
    }

    static final QueryString EMPTY = new QueryString(Collections.emptyMap());

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QueryString that = (QueryString) o;
        return Objects.equals(map, that.map);
    }

    @Override
    public int hashCode() {
        return Objects.hash(map);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        for (String key : map.keySet()) {
            String encodedKey = urlEncode(key);
            for (String value : map.get(key)) {
                if (sb.length() > 1) sb.append('&');
                sb.append(encodedKey).append('=').append(urlEncode(value));
            }
        }
        return sb.toString();
    }
}
