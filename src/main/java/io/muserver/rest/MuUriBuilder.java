package io.muserver.rest;

import io.muserver.Mutils;
import io.netty.handler.codec.http.QueryStringDecoder;

import javax.ws.rs.Path;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriBuilderException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toList;

class MuUriBuilder extends UriBuilder {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    private String scheme;
    private String userInfo;
    private String host;
    private int port = -1;
    private List<MuPathSegment> pathSegments = new ArrayList<>();
    private MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
    private String fragment;
    private boolean hasPrecedingSlash = false;
    private boolean hasTrailingSlash = false;

    MuUriBuilder() {
    }

    private MuUriBuilder(String scheme, String userInfo, String host, int port, List<MuPathSegment> pathSegments,
                         boolean hasPrecedingSlash, boolean hasTrailingSlash, MultivaluedMap<String, String> query, String fragment) {
        this.scheme = scheme;
        this.userInfo = userInfo;
        this.host = host;
        this.port = port;
        this.pathSegments = new ArrayList<>(pathSegments);
        this.hasTrailingSlash = hasTrailingSlash;
        this.hasPrecedingSlash = hasPrecedingSlash;
        MultivaluedMap<String, String> copy = new MultivaluedHashMap<>();
        copy.putAll(query);
        this.query = copy;
        this.fragment = fragment;
    }

    @Override
    public UriBuilder clone() {
        return new MuUriBuilder(scheme, userInfo, host, port, pathSegments, hasPrecedingSlash, hasTrailingSlash, query, fragment);
    }

    @Override
    public UriBuilder uri(URI uri) {
        scheme(uri.getScheme());
        userInfo(uri.getUserInfo());
        host(uri.getHost());
        port(uri.getPort());
        replacePath(uri.getPath());
        replaceQuery(uri.getQuery());
        fragment(uri.getFragment());
        return this;
    }

    @Override
    public UriBuilder uri(String uriTemplate) {
        if (uriTemplate == null) {
            throw new IllegalArgumentException("uriTemplate is null");
        }
        Pattern parts = Pattern.compile("(?<firstBit>[^/]*//[^/]*)?(?<path>[^?#]*)(?<end>.*)");
        Matcher matcher = parts.matcher(uriTemplate);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(uriTemplate + " is not a valid URI");
        }
        String path = matcher.group("path");
        UriBuilder builder = uri(URI.create(matcher.group("firstBit") + matcher.group("end")));
        builder.replacePath(path);
        return builder;
    }

    @Override
    public UriBuilder schemeSpecificPart(String ssp) {
        MuUriBuilder builder = (MuUriBuilder) fromUri(URI.create(scheme + "://" + ssp));
        this.scheme = builder.scheme;
        this.userInfo = builder.userInfo;
        this.host = builder.host;
        this.port = builder.port;
        this.pathSegments = builder.pathSegments;
        setSlashes(ssp);
        return this;
    }

    @Override
    public UriBuilder scheme(String scheme) {
        this.scheme = decode(scheme);
        return this;
    }

    @Override
    public UriBuilder userInfo(String userInfo) {
        this.userInfo = decode(userInfo);
        return this;
    }

    @Override
    public UriBuilder host(String host) {
        this.host = decode(host);
        return this;
    }

    @Override
    public UriBuilder port(int port) {
        this.port = port;
        return this;
    }

    @Override
    public UriBuilder path(String path) {
        Mutils.notNull("path", path);
        setSlashes(path);
        this.pathSegments.addAll(MuUriInfo.pathStringToSegments(decode(path), false).collect(toList()));
        return this;
    }

    @Override
    public UriBuilder replacePath(String path) {
        this.pathSegments.clear();
        return path(path);
    }

    @Override
    public UriBuilder path(Class resource) {
        Mutils.notNull("resource", resource);
        return this.path(findResourcePath(resource));
    }

    private String findResourcePath(Class resource) {
        Path pathAn = (Path)resource.getDeclaredAnnotation(Path.class);
        if (pathAn == null) {
            throw new IllegalArgumentException(resource + " is not a JAX-RS class");
        }
        return pathAn.value();
    }

    @Override
    public UriBuilder path(Class resource, String method) {
        Method selected = null;
        for (Method m : resource.getDeclaredMethods()) {
            if (m.getName().equals(method)) {
                if (selected == null) {
                    selected = m;
                } else {
                    throw new IllegalArgumentException("There is more than one method named " + method + " in " + resource);
                }
            }
        }
        if (selected != null) {
            return path(selected);
        }
        throw new IllegalArgumentException("Could not find " + resource + "#" + method);
    }

    @Override
    public UriBuilder path(Method method) {
        path(method.getDeclaringClass());
        Path methodPath = method.getDeclaredAnnotation(Path.class);
        if (methodPath != null) {
            path(methodPath.value());
        }
        return this;
    }

    @Override
    public UriBuilder segment(String... segments) {
        Mutils.notNull("segments", segments);
        for (String segment : segments) {
            Mutils.notNull("segment", segment);
            this.pathSegments.addAll(MuUriInfo.pathStringToSegments(decode(segment), true).collect(toList()));
        }
        this.hasTrailingSlash = false;
        return this;
    }

    @Override
    public UriBuilder replaceMatrix(String matrix) {
        MultivaluedMap<String, String> params = getOrCreateCurrentSegment().getMatrixParameters();
        params.clear();
        if (matrix != null) {
            String[] parts = matrix.split(";");
            for (String part : parts) {
                String[] nameValue = part.split("=");
                if (nameValue.length != 2) {
                    throw new IllegalArgumentException("Not a valid matrix string: " + matrix);
                }
                matrixParam(nameValue[0], nameValue[1]);
            }
        }
        return this;
    }

    @Override
    public UriBuilder matrixParam(String name, Object... values) {
        Mutils.notNull("name", name);
        Mutils.notNull("values", values);

        MultivaluedMap<String, String> params = getOrCreateCurrentSegment().getMatrixParameters();
        for (Object value : values) {
            params.add(name, decode(value));
        }

        return this;
    }

    @Override
    public UriBuilder replaceMatrixParam(String name, Object... values) {
        Mutils.notNull("name", name);
        Mutils.notNull("values", values);
        MultivaluedMap<String, String> params = getOrCreateCurrentSegment().getMatrixParameters();
        params.replace(name, Stream.of(values).map(Object::toString).collect(toList()));
        return this;
    }

    private MuPathSegment getOrCreateCurrentSegment() {
        MuPathSegment ps;
        if (pathSegments.isEmpty()) {
            ps = new MuPathSegment("", new MultivaluedHashMap<>());
            pathSegments.add(ps);
        } else {
            ps = pathSegments.get(pathSegments.size() - 1);
        }
        return ps;
    }

    @Override
    public UriBuilder replaceQuery(String qs) {
        if (qs == null) {
            this.query.clear();
        } else {
            query = new MultivaluedHashMap<>();
            QueryStringDecoder decoder = new QueryStringDecoder(qs, false);
            for (Map.Entry<String, List<String>> entry : decoder.parameters().entrySet()) {
                List<String> values = entry.getValue();
                queryParam(entry.getKey(), values.toArray(new Object[values.size()]));
            }
        }
        return this;
    }

    @Override
    public UriBuilder queryParam(String name, Object... values) {
        Mutils.notNull("name", name);
        Mutils.notNull("values", values);
        name = decode(name);
        for (Object value : values) {
            query.add(name, decode(value.toString()));
        }
        return this;
    }

    @Override
    public UriBuilder replaceQueryParam(String name, Object... values) {
        Mutils.notNull("name", name);
        query.remove(name);
        return values == null ? this : queryParam(name, values);
    }

    @Override
    public UriBuilder fragment(String fragment) {
        this.fragment = decode(fragment);
        return this;
    }


    @Override
    public UriBuilder resolveTemplate(String name, Object value) {
        Mutils.notNull("name", name);
        Mutils.notNull("value", value);
        return resolveTemplate(name, value, true);
    }

    @Override
    public UriBuilder resolveTemplateFromEncoded(String name, Object value) {
        Mutils.notNull("name", name);
        Mutils.notNull("value", value);
        String decoded = Jaxutils.leniantUrlDecode(value.toString());
        return resolveTemplate(name, decoded, true);
    }

    @Override
    public UriBuilder resolveTemplates(Map<String, Object> templateValues) {
        Mutils.notNull("templateValues", templateValues);
        return resolveTemplates(templateValues, true);
    }

    @Override
    public UriBuilder resolveTemplates(Map<String, Object> templateValues, boolean encodeSlashInPath) throws IllegalArgumentException {
        Mutils.notNull("templateValues", templateValues);
        for (Map.Entry<String, Object> entry : templateValues.entrySet()) {
            resolveTemplate(entry.getKey(), entry.getValue(), encodeSlashInPath);
        }
        return this;
    }

    @Override
    public UriBuilder resolveTemplatesFromEncoded(Map<String, Object> templateValues) {
        Mutils.notNull("templateValues", templateValues);
        for (Map.Entry<String, Object> entry : templateValues.entrySet()) {
            resolveTemplateFromEncoded(entry.getKey(), entry.getValue());
        }
        return this;
    }

    @Override
    public UriBuilder resolveTemplate(String name, Object value, boolean encodeSlashInPath) {
        Mutils.notNull("name", name);
        Mutils.notNull("value", value);
        String val = value.toString();

        scheme = resolve(scheme, name, val);
        userInfo = resolve(userInfo, name, val);
        host = resolve(host, name, val);
        pathSegments = pathSegments.stream()
            .flatMap(ps -> ps.resolve(name, val, encodeSlashInPath).stream())
            .collect(toList());
        if (!query.isEmpty()) {
            MultivaluedMap<String, String> nq = new MultivaluedHashMap<>();
            for (Map.Entry<String, List<String>> entry : query.entrySet()) {
                String key = resolve(entry.getKey(), name, val);
                nq.addAll(key, entry.getValue().stream().map(s -> resolve(s, name, val)).collect(toList()));
            }
            query = nq;
        }
        fragment = resolve(fragment, name, val);

        return this;
    }

    @Override
    public URI buildFromMap(Map<String, ?> values) {
        return buildFromMap(values, true);
    }

    @Override
    public URI buildFromMap(Map<String, ?> values, boolean encodeSlashInPath) throws IllegalArgumentException, UriBuilderException {
        MuUriBuilder resolved = cloneAndResolve(values, encodeSlashInPath, false);
        return URI.create(resolved.buildIt(Mutils::urlEncode));
    }

    @Override
    public URI buildFromEncodedMap(Map<String, ?> values) throws IllegalArgumentException, UriBuilderException {
        MuUriBuilder resolved = cloneAndResolve(values, true, true);
        return URI.create(resolved.buildIt(Mutils::urlEncode));

    }

    @Override
    public URI build(Object... values) throws IllegalArgumentException, UriBuilderException {
        return build(values, true);
    }

    @Override
    public URI build(Object[] values, boolean encodeSlashInPath) throws IllegalArgumentException, UriBuilderException {
        Map<String, ?> valueMap = valuesToMap(values);
        return buildFromMap(valueMap, encodeSlashInPath);
    }

    @Override
    public URI buildFromEncoded(Object... values) throws IllegalArgumentException, UriBuilderException {
        Map<String, ?> valueMap = valuesToMap(values);
        return buildFromEncodedMap(valueMap);
    }

    @Override
    public String toTemplate() {
        return buildIt(s -> s);
    }


    private MuUriBuilder cloneAndResolve(Map<String, ?> values, boolean encodeSlashInPath, boolean valuesAlreadyEncoded) {
        if (values.isEmpty()) {
            return this;
        }
        MuUriBuilder copy = (MuUriBuilder) clone();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (valuesAlreadyEncoded) {
                value = Jaxutils.leniantUrlDecode(value.toString());
            }
            copy.resolveTemplate(entry.getKey(), value, encodeSlashInPath);
        }
        return copy;
    }

    static String resolve(String template, String name, String value) {
        if (template == null) {
            return null;
        }
        return template.replaceAll("\\{\\s*" + Pattern.quote(name) + "\\s*(:[^}]*)?\\s*}", value);
    }


    private String buildIt(Function<String, String> encodeFunction) {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) {
            sb.append(encodeFunction.apply(scheme)).append("://");
        }
        if (userInfo != null) {
            String encodedUserInfo = encodeFunction.apply(userInfo);
            // If there is a colon in a user:pw pair, no way to know if it belongs to
            // the name or password, so just assume password.
            encodedUserInfo = encodedUserInfo.replaceFirst("%3A", ":");
            sb.append(encodedUserInfo).append('@');
        }
        if (host != null) {
            sb.append(encodeFunction.apply(host));
        }
        if (port != -1) {
            sb.append(':').append(port);
        }
        if (!pathSegments.isEmpty()) {
            if (hasPrecedingSlash || sb.length() > 0) {
                sb.append('/');
            }
            sb.append(pathSegments.stream()
                .map(muPathSegment -> muPathSegment.toString(encodeFunction))
                .collect(Collectors.joining("/")));
        }
        if (hasTrailingSlash) {
            sb.append('/');
        }
        if (!query.isEmpty()) {
            sb.append('?');
            boolean isFirst = true;
            for (Map.Entry<String, List<String>> entry : query.entrySet()) {
                String key = encodeFunction.apply(entry.getKey());
                for (String val : entry.getValue()) {
                    if (!isFirst) {
                        sb.append('&');
                    }
                    sb.append(key).append('=').append(encodeFunction.apply(val));
                    isFirst = false;
                }
            }
        }
        if (fragment != null) {
            sb.append('#').append(encodeFunction.apply(fragment));
        }
        return sb.toString();
    }

    private Map<String, ?> valuesToMap(Object[] values) {
        if (values == null || values.length == 0) {
            return emptyMap();
        }
        List<String> sorted = new ArrayList<>();
        addTemplateNames(sorted, scheme);
        addTemplateNames(sorted, userInfo);
        addTemplateNames(sorted, host);
        for (MuPathSegment pathSegment : pathSegments) {
            for (String pspp : pathSegment.pathParameters()) {
                if (!sorted.contains(pspp)) {
                    sorted.add(pspp);
                }
            }
        }
        for (Map.Entry<String, List<String>> entry : query.entrySet()) {
            addTemplateNames(sorted, entry.getKey());
            for (String val : entry.getValue()) {
                addTemplateNames(sorted, val);
            }
        }
        addTemplateNames(sorted, fragment);

        if (sorted.size() != values.length) {
            throw new IllegalArgumentException("There are " + sorted.size() + " parameters (" + sorted + ") but " + values.length + " values " + Arrays.toString(values) + " were supplied.");
        }
        HashMap<String, Object> map = new HashMap<>();
        int index = 0;
        for (String name : sorted) {
            Object value = values[index];
            if (value == null) {
                throw new IllegalArgumentException("Value at index " + index + " was null");
            }
            map.put(name, value);
            index++;
        }
        return map;
    }

    private static void addTemplateNames(List<String> sorted, String value) {
        if (value != null) {
            List<String> toAdd = UriPattern.uriTemplateToRegex(value).namedGroups();
            for (String s : toAdd) {
                if (!sorted.contains(s)) {
                    sorted.add(s);
                }
            }
        }
    }

    private static String decode(Object value) {
        return value == null ? null : Jaxutils.leniantUrlDecode(value.toString());
    }

    private void setSlashes(String path) {
        if (this.pathSegments.isEmpty()) {
            this.hasPrecedingSlash = path.startsWith("/");
        }
        this.hasTrailingSlash = path.endsWith("/");
    }

}
