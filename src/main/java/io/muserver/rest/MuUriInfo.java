package io.muserver.rest;

import io.muserver.Mutils;
import io.netty.handler.codec.http.QueryStringDecoder;

import javax.ws.rs.core.*;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.muserver.Mutils.urlDecode;
import static io.muserver.rest.ReadOnlyMultivaluedMap.readOnly;
import static java.util.stream.Collectors.toList;

class MuUriInfo implements UriInfo {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    private final URI baseUri;
    private final URI requestUri;
    private final String encodedRelativePath;
    private final List<String> matchedURIs;
    private final List<Object> matchedResources;

    MuUriInfo(URI baseUri, URI requestUri, String encodedRelativePath, List<String> matchedURIs, List<Object> matchedResources) {
        this.baseUri = baseUri;
        this.requestUri = requestUri;
        this.encodedRelativePath = encodedRelativePath;
        this.matchedURIs = matchedURIs;
        this.matchedResources = matchedResources;
    }

    @Override
    public String getPath() {
        return getPath(true);
    }

    @Override
    public String getPath(boolean decode) {
        return decode ? urlDecode(encodedRelativePath) : encodedRelativePath;
    }

    @Override
    public List<PathSegment> getPathSegments() {
        return getPathSegments(true);
    }

    @Override
    public List<PathSegment> getPathSegments(boolean decode) {
        return pathStringToSegments(getPath(decode), false)
            .collect(Collectors.toList());
    }

    static Stream<MuPathSegment> pathStringToSegments(String path, boolean encodeSlashes) {
        Stream<String> stream = encodeSlashes ? Stream.of(path) : Stream.of(path.split("/"));
        return stream
            .filter(s -> !s.isEmpty())
            .map(s -> {
                String[] segments = s.split(";");

                MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
                for (int i = 1; i < segments.length; i++) {
                    String[] nv = segments[i].split("=");
                    String paramName = nv[0];
                    if (nv.length == 1) {
                        params.add(paramName, "");
                    } else {
                        String[] vals = nv[1].split(",");
                        params.addAll(paramName, vals);
                    }
                }
                return new MuPathSegment(segments[0], params);
            });
    }

    @Override
    public URI getRequestUri() {
        return requestUri;
    }

    @Override
    public UriBuilder getRequestUriBuilder() {
        return UriBuilder.fromUri(requestUri);
    }

    @Override
    public URI getAbsolutePath() {
        return getBaseUri().resolve(getPath(false));
    }

    @Override
    public UriBuilder getAbsolutePathBuilder() {
        return UriBuilder.fromUri(getAbsolutePath());
    }

    @Override
    public URI getBaseUri() {
        return baseUri;
    }

    @Override
    public UriBuilder getBaseUriBuilder() {
        return UriBuilder.fromUri(baseUri);
    }

    @Override
    public MultivaluedMap<String, String> getPathParameters() {
        return getPathParameters(true);
    }

    @Override
    public MultivaluedMap<String, String> getPathParameters(boolean decode) {
        MultivaluedMap<String, String> all = new MultivaluedHashMap<>();
        getPathSegments(decode).forEach(seg -> all.putAll(seg.getMatrixParameters()));
        return readOnly(all);
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters() {
        return getQueryParameters(true);
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters(boolean decode) {
        QueryStringDecoder qsd = new QueryStringDecoder(requestUri);
        MultivaluedHashMap<String, String> all = new MultivaluedHashMap<>();
        if (decode) {
            all.putAll(qsd.parameters());
        } else {
            for (Map.Entry<String, List<String>> entry : qsd.parameters().entrySet()) {
                all.put(entry.getKey(), entry.getValue().stream().map(Mutils::urlEncode).collect(toList()));
            }
        }
        return readOnly(all);
    }

    @Override
    public List<String> getMatchedURIs() {
        return getMatchedURIs(true);
    }

    @Override
    public List<String> getMatchedURIs(boolean decode) {
        if (decode) {
            return matchedURIs;
        }
        throw NotImplementedException.notYet();
    }

    @Override
    public List<Object> getMatchedResources() {
        return matchedResources;
    }

    @Override
    public URI resolve(URI relative) {
        return this.requestUri.resolve(relative);
    }

    @Override
    public URI relativize(URI uri) {
        URI relToBase = this.baseUri.resolve(uri);
        URI requestUriDir = this.requestUri.resolve(URI.create("."));
        return requestUriDir.relativize(relToBase);
    }

    @Override
    public String toString() {
        return getRequestUri().toString();
    }
}
