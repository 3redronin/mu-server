package io.muserver.rest;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.PathSegment;
import java.util.Objects;

public class MuPathSegment implements PathSegment {
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
        return "MuPathSegment{" +
            "path='" + path + '\'' +
            ", params=" + params +
            '}';
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
