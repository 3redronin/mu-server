package io.muserver.rest;


import io.muserver.HeaderNames;
import io.muserver.Headers;
import io.muserver.MuException;
import io.muserver.Mutils;
import io.netty.handler.codec.http.HttpHeaderNames;

import javax.ws.rs.core.*;
import javax.ws.rs.ext.RuntimeDelegate;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toMap;

class JaxRSResponse extends Response {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    private final MultivaluedMap<String, Object> headers;
    private final StatusType status;
    private final Object entity;
    private final MediaType type;
    private final NewCookie[] cookies;
    private final List<Link> links;
    private final Annotation[] annotations;

    JaxRSResponse(StatusType status, MultivaluedMap<String, Object> headers, Object entity, MediaType type, NewCookie[] cookies, List<Link> links, Annotation[] annotations) {
        this.status = status;
        this.headers = headers;
        this.entity = entity;
        this.type = type;
        this.cookies = cookies;
        this.links = links;
        this.annotations = annotations;
    }

    public Annotation[] getAnnotations() {
        if (annotations == null) return new Annotation[0];
        return annotations;
    }

    @Override
    public int getStatus() {
        return status.getStatusCode();
    }

    @Override
    public StatusType getStatusInfo() {
        return status;
    }

    @Override
    public Object getEntity() {
        return entity;
    }

    @Override
    public <T> T readEntity(Class<T> entityType) {
        throw NotImplementedException.willNot();
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType) {
        throw NotImplementedException.willNot();
    }

    @Override
    public <T> T readEntity(Class<T> entityType, Annotation[] annotations) {
        throw NotImplementedException.willNot();
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) {
        throw NotImplementedException.willNot();
    }

    @Override
    public boolean hasEntity() {
        return entity != null;
    }

    @Override
    public boolean bufferEntity() {
        throw NotImplementedException.willNot();
    }

    @Override
    public void close() {
        throw NotImplementedException.notYet();
    }

    @Override
    public MediaType getMediaType() {
        return type;
    }

    @Override
    public Locale getLanguage() {
        String h = getHeaderString(HeaderNames.CONTENT_LANGUAGE.toString());
        if (h == null) return null;
        return Locale.forLanguageTag(h);
    }

    @Override
    public int getLength() {
        String l = getHeaderString(HeaderNames.CONTENT_LENGTH.toString());
        if (l == null) return -1;
        try {
            return Integer.parseInt(l);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public Set<String> getAllowedMethods() {
        String allow = getHeaderString(HeaderNames.ALLOW.toString());
        return allow == null ? Collections.emptySet() : new HashSet<>(asList(allow.split(",")));
    }

    @Override
    public Map<String, NewCookie> getCookies() {
        return Stream.of(cookies).collect(toMap(Cookie::getName, c -> c));
    }

    @Override
    public EntityTag getEntityTag() {
        Object first = headers.getFirst(HeaderNames.ETAG.toString());
        if (first == null || first instanceof  EntityTag) return (EntityTag)first;
        return EntityTag.valueOf(first.toString());
    }

    @Override
    public Date getDate() {
        return dateFromHeader("date");
    }

    private Date dateFromHeader(String name) {
        Object date = headers.getFirst(name);
        if (date == null || date.getClass().isAssignableFrom(Date.class)) return (Date)date;
        return Mutils.fromHttpDate(date.toString());
    }

    @Override
    public Date getLastModified() {
        return dateFromHeader("last-modified");
    }

    @Override
    public URI getLocation() {
        String s = getHeaderString("location");
        return s == null ? null : URI.create(s);
    }

    @Override
    public Set<Link> getLinks() {
        return new HashSet<>(links);
    }

    @Override
    public boolean hasLink(String relation) {
        return links.stream().anyMatch(link -> link.getRels().contains(relation));
    }

    @Override
    public Link getLink(String relation) {
        return links.stream().filter(link -> link.getRels().contains(relation)).findFirst().orElse(null);
    }

    @Override
    public Link.Builder getLinkBuilder(String relation) {
        Link link = getLink(relation);
        if (link == null) {
            return null;
        }
        return Link.fromLink(link);
    }

    @Override
    public MultivaluedMap<String, Object> getMetadata() {
        return headers;
    }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
        for (Map.Entry<String, List<Object>> entry : headers.entrySet()) {
            map.put(entry.getKey(), entry.getValue()
                .stream()
                .map(JaxRSResponse::headerValueToString)
                .collect(Collectors.toList())
            );
        }
        return map;
    }

    static MultivaluedMap<String, Object> muHeadersToJaxObj(Headers headers) {
        MultivaluedMap<String, Object> map = new MultivaluedHashMap<>();
        for (String name : headers.names()) {
            map.addAll(name, headers.getAll(name));
        }
        return map;
    }

    @Override
    public String getHeaderString(String name) {
        return headerValueToString(headers.getFirst(name));
    }

    private static String headerValueToString(Object value) {
        if (value == null || value instanceof String) {
            return (String)value;
        }
        if (value.getClass().isAssignableFrom(Date.class)) {
            return Mutils.toHttpDate((Date)value);
        }
        try {
            RuntimeDelegate.HeaderDelegate headerDelegate = MuRuntimeDelegate.getInstance().createHeaderDelegate(value.getClass());
            return headerDelegate.toString(value);
        } catch (MuException e) {
            return value.toString();
        }
    }

    public static class Builder extends Response.ResponseBuilder {
        static {
            MuRuntimeDelegate.ensureSet();
        }

        private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        private final List<Link> linkHeaders = new ArrayList<>();
        private StatusType status;
        private Object entity;
        private Annotation[] annotations;
        private NewCookie[] cookies = new NewCookie[0];
        private MediaType type;
        private List<Variant> variants = new ArrayList<>();

        @Override
        public Response build() {
            for (Link linkHeader : linkHeaders) {
                headers.add(HeaderNames.LINK.toString(), linkHeader.toString());
            }
            MediaType typeToUse = this.type;
            if (typeToUse == null) {
                String ct = (String) headers.getFirst(HeaderNames.CONTENT_TYPE.toString());
                if (ct != null) {
                    typeToUse = MediaType.valueOf(ct);
                }
            } else {
                headers.replace(HeaderNames.CONTENT_TYPE.toString(), Collections.singletonList(typeToUse.toString()));
            }
            return new JaxRSResponse(status, headers, entity, typeToUse, cookies, linkHeaders, annotations);
        }

        @Override
        public ResponseBuilder clone() {
            throw NotImplementedException.notYet();
        }

        @Override
        public ResponseBuilder status(int code) {
            return status(code, null);
        }

        @Override
        public ResponseBuilder status(int code, String reasonPhrase) {
            if (code < 100 || code > 599) {
                throw new IllegalArgumentException("Status must be between 100 and 599, but was " + code);
            }
            this.status = Status.fromStatusCode(code);
            if (this.status == null || reasonPhrase != null) {
                this.status = new CustomStatus(Status.Family.familyOf(code), code, reasonPhrase);
            }
            return this;
        }

        @Override
        public ResponseBuilder entity(Object entity) {
            this.entity = entity;
            this.annotations = null;
            return this;
        }

        @Override
        public ResponseBuilder entity(Object entity, Annotation[] annotations) {
            this.entity = entity;
            this.annotations = annotations;
            return this;
        }

        @Override
        public ResponseBuilder allow(String... methods) {
            if (methods == null || (methods.length == 1 && methods[0] == null)) {
                return allow((Set<String>) null);
            } else {
                return allow(new HashSet<>(Arrays.asList(methods)));
            }
        }

        @Override
        public ResponseBuilder allow(Set<String> methods) {
            if (methods == null) {
                return header(HttpHeaderNames.ALLOW, null);
            }

            StringBuilder allow = new StringBuilder();
            for (String m : methods) {
                append(allow, true, m);
            }
            return header(HttpHeaderNames.ALLOW, allow.toString());
        }

        private void append(StringBuilder sb, boolean v, String s) {
            if (v) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(s);
            }
        }


        @Override
        public ResponseBuilder cacheControl(CacheControl cacheControl) {
            return header(HeaderNames.CACHE_CONTROL, cacheControl.toString());
        }

        @Override
        public ResponseBuilder encoding(String encoding) {
            return header(HeaderNames.CONTENT_ENCODING, encoding);
        }

        private ResponseBuilder header(CharSequence name, Object value) {
            if (value instanceof Iterable) {
                ((Iterable) value).forEach(v -> header(name, v));
            } else {
                if (value == null) {
                    headers.remove(name.toString());
                } else {
                    headers.add(name.toString(), value);
                }
            }
            return this;
        }

        @Override
        public ResponseBuilder header(String name, Object value) {
            return header((CharSequence) name.toLowerCase(), value);
        }

        @Override
        public ResponseBuilder replaceAll(MultivaluedMap<String, Object> headers) {
            this.headers.clear();
            if (headers != null) {
                for (Map.Entry<String, List<Object>> entry : headers.entrySet()) {
                    this.headers.add(entry.getKey().toLowerCase(), entry.getValue());
                }
            }
            return this;
        }

        @Override
        public ResponseBuilder language(String language) {
            return header(HeaderNames.CONTENT_LANGUAGE, language);
        }

        @Override
        public ResponseBuilder language(Locale language) {
            return language(language.toLanguageTag());
        }

        @Override
        public ResponseBuilder type(MediaType type) {
            this.type = type;
            return this;
        }

        @Override
        public ResponseBuilder type(String type) {
            if (type == null) {
                this.type = null;
                return this;
            }
            return type(MediaType.valueOf(type));
        }

        @Override
        public ResponseBuilder variant(Variant variant) {
            this.variants.add(variant);
            return this;
        }

        @Override
        public ResponseBuilder contentLocation(URI location) {
            return header(HeaderNames.CONTENT_LOCATION, location);
        }

        @Override
        public ResponseBuilder cookie(NewCookie... cookies) {
            this.cookies = cookies;
            if (cookies == null) {
                headers.remove(HeaderNames.SET_COOKIE.toString());
            }
            return this;
        }

        @Override
        public ResponseBuilder expires(Date expires) {
            return header(HeaderNames.EXPIRES, expires);
        }

        @Override
        public ResponseBuilder lastModified(Date lastModified) {
            return header(HeaderNames.LAST_MODIFIED, lastModified);
        }

        @Override
        public ResponseBuilder location(URI location) {
            return header(HeaderNames.LOCATION, location);
        }

        @Override
        public ResponseBuilder tag(EntityTag tag) {
            return tag(tag.toString());
        }

        @Override
        public ResponseBuilder tag(String tag) {
            return header(HeaderNames.ETAG, tag);
        }

        @Override
        public ResponseBuilder variants(Variant... variants) {
            return variants(asList(variants));
        }

        @Override
        public ResponseBuilder variants(List<Variant> variants) {
            if (variants == null) {
                this.variants.clear();
            } else {
                this.variants.addAll(variants);
            }
            return this;
        }

        @Override
        public ResponseBuilder links(Link... links) {
            if (links == null) {
                linkHeaders.clear();
            } else {
                linkHeaders.addAll(asList(links));
            }
            return this;
        }

        @Override
        public ResponseBuilder link(URI uri, String rel) {
            Link link = Link.fromUri(uri).rel(rel).build();
            linkHeaders.add(link);
            return this;
        }

        @Override
        public ResponseBuilder link(String uri, String rel) {
            return link(URI.create(uri), rel);
        }
    }

    private static class CustomStatus implements StatusType {
        private final String reason;
        private final Status.Family family;
        private final int code;

        private CustomStatus(Status.Family family, int code, String reason) {
            this.reason = reason;
            this.family = family;
            this.code = code;
        }

        @Override
        public int getStatusCode() {
            return code;
        }

        @Override
        public Status.Family getFamily() {
            return family;
        }

        @Override
        public String getReasonPhrase() {
            return reason;
        }
    }
}
