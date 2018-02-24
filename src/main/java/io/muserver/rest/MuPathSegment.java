package io.muserver.rest;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.PathSegment;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;

class MuPathSegment implements PathSegment {
    private final String path;
    private final MultivaluedMap<String, String> params;
    private final List<String> pathParams;

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
        return path + getMatrixString(s -> s);
    }
    public String toString(Function<String, String> encodeFunction) {
        return encodeFunction.apply(path) + getMatrixString(encodeFunction);
    }

    private String getMatrixString(Function<String, String> encodeFunction) {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<Map.Entry<String, List<String>>> entries = params.entrySet().stream()
            .sorted(Comparator.comparing(Map.Entry::getKey))
            .collect(Collectors.toList());
        for (Map.Entry<String, List<String>> param : entries) {
            String encodedKey = encodeFunction.apply(param.getKey());
            for (String val : param.getValue()) {
                sb.append(';').append(encodedKey).append('=').append(encodeFunction.apply(val));
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

    public List<String> pathParameters() {
        return pathParams;
    }

    public List<MuPathSegment> resolve(String name, String value, boolean encodeSlashInPath) {
        String newPath = MuUriBuilder.resolve(path, name, value);
        MultivaluedMap<String, String> newParams = new MultivaluedHashMap<>();
        newParams.putAll(this.params);
        if (encodeSlashInPath) {
            return Collections.singletonList(new MuPathSegment(newPath, newParams));
        }
        String[] newPaths = newPath.split("/");
        List<MuPathSegment> list = new ArrayList<>();
        for (int i = 0; i < newPaths.length; i++) {
            String s = newPaths[i];
            MultivaluedMap<String, String> p = i == 0 ? newParams : ReadOnlyMultivaluedMap.empty();
            MuPathSegment muPathSegment = new MuPathSegment(s, p);
            list.add(muPathSegment);
        }
        return list;
    }
}
