package io.muserver.rest;

import io.netty.handler.codec.http.QueryStringDecoder;

import javax.ws.rs.core.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.muserver.Mutils.urlEncode;

class MuUriBuilder extends UriBuilder {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    private String scheme;
    private String userInfo;
    private String host;
    private int port;
    private List<PathSegment> pathSegments = new ArrayList<>();
    private MultivaluedMap<String, String> query = new MultivaluedHashMap<>();

    @Override
    public UriBuilder clone() {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder uri(URI uri) {
        this.scheme = uri.getScheme();
        this.userInfo = uri.getUserInfo();
        this.host = uri.getHost();
        this.port = uri.getPort();
        pathSegments = MuUriInfo.pathStringToSegments(uri.getPath(), false);

        replaceQuery(uri.getQuery());
        return this;
    }

    @Override
    public UriBuilder uri(String uriTemplate) {
        throw NotImplementedException.notYet();
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
        this.pathSegments.addAll(MuUriInfo.pathStringToSegments(path, false));
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
            this.pathSegments.addAll(MuUriInfo.pathStringToSegments(segment, true));
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
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder fragment(String fragment) {
        throw NotImplementedException.notYet();
    }

    @Override
    public UriBuilder resolveTemplate(String name, Object value) {
        throw NotImplementedException.notYet();
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
        throw NotImplementedException.notYet();
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
        throw NotImplementedException.notYet();
    }

    @Override
    public URI buildFromMap(Map<String, ?> values, boolean encodeSlashInPath) throws IllegalArgumentException, UriBuilderException {
        throw NotImplementedException.notYet();
    }

    @Override
    public URI buildFromEncodedMap(Map<String, ?> values) throws IllegalArgumentException, UriBuilderException {
        throw NotImplementedException.notYet();
    }

    @Override
    public URI build(Object... values) throws IllegalArgumentException, UriBuilderException {
        return build(values, true);
    }

    @Override
    public URI build(Object[] values, boolean encodeSlashInPath) throws IllegalArgumentException, UriBuilderException {
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
            .map(Object::toString)
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
        return URI.create(sb.toString());
    }

    @Override
    public URI buildFromEncoded(Object... values) throws IllegalArgumentException, UriBuilderException {
        throw NotImplementedException.notYet();
    }

    @Override
    public String toTemplate() {
        throw NotImplementedException.notYet();
    }
}
