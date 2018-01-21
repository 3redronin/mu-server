package io.muserver.rest;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.PathSegment;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.muserver.Mutils.urlEncode;

class MuPathSegment implements PathSegment {
    private final String path;
    private final MultivaluedMap<String, String> params;

    public MuPathSegment(String path, MultivaluedMap<String, String> params) {
        this.params = params;
        this.path = path;
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
        if (params.isEmpty()) {
            return urlEncode(path);
        }
        StringBuilder sb = new StringBuilder(path);
        for (Map.Entry<String, List<String>> param : params.entrySet()) {
            String key = urlEncode(param.getKey());
            for (String val : param.getValue()) {
                sb.append(';').append(key).append('=').append(urlEncode(val));
            }
        }
        return sb.toString();
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
}
