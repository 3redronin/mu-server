package io.muserver.rest;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.PathSegment;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static io.muserver.Mutils.urlDecode;
import static io.muserver.Mutils.urlEncode;
import static java.util.Collections.emptyList;

class MuPathSegment implements PathSegment {
    private final String path;
    private final MultivaluedMap<String, String> params;
    private List<String> pathParams;

    MuPathSegment(String path, MultivaluedMap<String, String> params) {
        this.params = params;
        this.path = path;
        if (path.contains("{")) {
            pathParams = UriPattern.uriTemplateToRegex(path).namedGroups();
        } else {
            pathParams = emptyList();
        }
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public MultivaluedMap<String, String> getMatrixParameters() {
        return params;
    }

    @Override
    public String toString() {
        return render(null, true, false, true);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MuPathSegment that = (MuPathSegment) o;
        return Objects.equals(path, that.path) && Objects.equals(params, that.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, params);
    }

    public List<String> pathParameters() {
        return pathParams;
    }

    public String render(Map<String, ?> values, boolean encodePath, boolean encodeValues, boolean encodeSlashInPath) {
        String cur = path;
        if (values != null) {
            for (String pathParam : pathParams) {
                Object val = values.get(pathParam);
                if (val != null) {
                    String replacement = val.toString();
                    if (encodePath && !encodeValues) {
                        replacement = urlDecode(replacement);
                    }
                    cur = cur.replaceAll("\\{\\s*" + Pattern.quote(pathParam) + "\\s*(:[^}]*)?\\s*}", replacement);
                }
            }
        }
        String pathBit = encodePath ? urlEncode(cur) : cur;
        if (!encodeSlashInPath) {
            pathBit = pathBit.replace("%2F", "/");
        }

        StringBuilder sb = new StringBuilder(pathBit);

        if (!params.isEmpty()) {
            for (Map.Entry<String, List<String>> param : params.entrySet()) {
                String key = urlEncode(param.getKey());
                for (String val : param.getValue()) {
                    sb.append(';').append(key).append('=').append(urlEncode(val));
                }
            }
        }
        return sb.toString();
    }
}
