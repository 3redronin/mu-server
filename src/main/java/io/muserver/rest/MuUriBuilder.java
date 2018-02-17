package io.muserver.rest;

import com.sun.deploy.util.OrderedHashSet;
import io.netty.handler.codec.http.QueryStringDecoder;

import javax.ws.rs.core.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.muserver.Mutils.urlEncode;
import static java.util.Collections.emptyMap;

class MuUriBuilder extends UriBuilder {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    private String scheme;
    private String userInfo;
    private String host;
    private int port;
    private List<MuPathSegment> pathSegments = new ArrayList<>();
    private MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
    private String fragment;

    MuUriBuilder() {
    }

    private MuUriBuilder(String scheme, String userInfo, String host, int port, List<MuPathSegment> pathSegments,
                         MultivaluedMap<String, String> query, String fragment) {
        this.scheme = scheme;
        this.userInfo = userInfo;
        this.host = host;
        this.port = port;
        this.pathSegments = new ArrayList<>(pathSegments);
        MultivaluedMap<String, String> copy = new MultivaluedHashMap<>();
        copy.putAll(query);
        this.query = copy;
        this.fragment = fragment;
    }

    @Override
    public UriBuilder clone() {
        return new MuUriBuilder(scheme, userInfo, host, port, pathSegments, query, fragment);
    }

    @Override
    public UriBuilder uri(URI uri) {
        scheme(uri.getScheme());
        userInfo(uri.getUserInfo());
        host(uri.getHost());
        port(uri.getPort());
        pathSegments = MuUriInfo.pathStringToSegments(uri.getPath(), false).collect(Collectors.toList());

        replaceQuery(uri.getQuery());
        fragment(uri.getFragment());
        return this;
    }

    @Override
    public UriBuilder uri(String uriTemplate) {
        if (uriTemplate == null) {
            throw new IllegalArgumentException("uriTemplate is null");
        }
        Pattern parts = Pattern.compile("(?<firstBit>[^/]*//[^/]*)(?<path>[^?#]*)(?<end>.*)");
        Matcher matcher = parts.matcher(uriTemplate);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(uriTemplate + " is not a valid URI");
        }
        String path = matcher.group("path");

        UriBuilder builder = uri(URI.create(matcher.group("firstBit") + matcher.group("end")));
        if (!path.isEmpty()) {
            builder.replacePath(path);
        }
        return builder;
    }

    @Override
    public UriBuilder scheme(String scheme) {
        this.scheme = scheme;
        return this;
    }

    @Override
    public UriBuilder schemeSpecificPart(String ssp) {
        MuUriBuilder builder = (MuUriBuilder) fromUri(URI.create(scheme + "://" + ssp));
        this.scheme = builder.scheme;
        this.userInfo = builder.userInfo;
        this.host = builder.host;
        this.port = builder.port;
        this.pathSegments = builder.pathSegments;
        return this;
    }

    @Override
    public UriBuilder userInfo(String userInfo) {
        this.userInfo = userInfo;
        return this;
    }

    @Override
    public UriBuilder host(String host) {
        this.host = host;
        return this;
    }

    @Override
    public UriBuilder port(int port) {
        this.port = port;
        return this;
    }

    @Override
    public UriBuilder replacePath(String path) {
        this.pathSegments.clear();
        return path(path);
    }

    @Override
    public UriBuilder path(String path) {
        this.pathSegments.addAll(MuUriInfo.pathStringToSegments(path, false).collect(Collectors.toList()));
        return this;
    }

    @Override
    public UriBuilder path(Class resource) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder path(Class resource, String method) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder path(Method method) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder segment(String... segments) {
        for (String segment : segments) {
            this.pathSegments.addAll(MuUriInfo.pathStringToSegments(segment, true).collect(Collectors.toList()));
        }
        return this;
    }

    @Override
    public UriBuilder replaceMatrix(String matrix) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder matrixParam(String name, Object... values) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder replaceMatrixParam(String name, Object... values) {
        throw NotImplementedException.notYet();
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
        for (Object value : values) {
            query.add(name, value.toString());
        }
        return this;
    }

    @Override
    public UriBuilder replaceQueryParam(String name, Object... values) {
        query.remove(name);
        return values == null ? this : queryParam(name, values);
    }

    @Override
    public UriBuilder fragment(String fragment) {
        this.fragment = fragment;
        return this;
    }

    @Override
    public UriBuilder resolveTemplate(String name, Object value) {
        return resolveTemplate(name, value, true);
    }

    @Override
    public UriBuilder resolveTemplate(String name, Object value, boolean encodeSlashInPath) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder resolveTemplateFromEncoded(String name, Object value) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder resolveTemplates(Map<String, Object> templateValues) {
        return resolveTemplates(templateValues, true);
    }

    @Override
    public UriBuilder resolveTemplates(Map<String, Object> templateValues, boolean encodeSlashInPath) throws IllegalArgumentException {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder resolveTemplatesFromEncoded(Map<String, Object> templateValues) {
        throw NotImplementedException.notYet();
    }

    @Override
    public URI buildFromMap(Map<String, ?> values) {
        return buildFromMap(values, true);
    }

    @Override
    public URI buildFromMap(Map<String, ?> values, boolean encodeSlashInPath) throws IllegalArgumentException, UriBuilderException {
        return buildIt(values, false, encodeSlashInPath);
    }

    private URI buildIt(Map<String, ?> values, boolean encodeValues, boolean encodeSlashInPath) {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) {
            sb.append(scheme).append("://");
        }
        if (userInfo != null) {
            sb.append(userInfo).append('@');
        }
        if (host != null) {
            sb.append(host);
        }
        if (port != -1) {
            sb.append(':').append(port);
        }
        String path = "/" + pathSegments.stream()
            .map(muPathSegment -> muPathSegment.render(values, encodeValues, encodeSlashInPath))
            .collect(Collectors.joining("/"));
        sb.append(path);
        if (!query.isEmpty()) {
            sb.append('?');
            boolean isFirst = true;
            for (Map.Entry<String, List<String>> entry : query.entrySet()) {
                String key = urlEncode(entry.getKey());
                for (String val : entry.getValue()) {
                    if (!isFirst) {
                        sb.append('&');
                    }
                    sb.append(key).append('=').append(urlEncode(val));
                    isFirst = false;
                }
            }
        }
        if (fragment != null) {
            sb.append('#').append(urlEncode(fragment));
        }
        return URI.create(sb.toString());
    }

    @Override
    public URI buildFromEncodedMap(Map<String, ?> values) throws IllegalArgumentException, UriBuilderException {
        return buildIt(values, false, false);
    }

    @Override
    public URI build(Object... values) throws IllegalArgumentException, UriBuilderException {
        return build(values, true);
    }

    @Override
    public URI build(Object[] values, boolean encodeSlashInPath) throws IllegalArgumentException, UriBuilderException {
        return buildIt(valuesToMap(values), true, encodeSlashInPath);
    }

    private Map<String, ?> valuesToMap(Object[] values) {
        if (values == null || values.length == 0) {
            return emptyMap();
        }
        SortedSet<String> sorted = pathSegments.stream()
            .flatMap(ps -> ps.pathParameters().stream())
            .collect(Collectors.toCollection(TreeSet::new));

        if (sorted.size() != values.length) {
            throw new IllegalArgumentException("There are " + sorted.size() + " paramters but " + values.length + " values were supplied.");
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

    @Override
    public URI buildFromEncoded(Object... values) throws IllegalArgumentException, UriBuilderException {
        return buildIt(valuesToMap(values), false, true);
    }

    @Override
    public String toTemplate() {
        throw NotImplementedException.notYet();
    }
}
