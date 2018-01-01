package ronin.muserver.rest;


import ronin.muserver.HeaderNames;
import ronin.muserver.Headers;

import javax.ws.rs.core.*;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.*;

import static java.util.Arrays.asList;

class JaxRSResponse extends Response {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    final Headers headers;
    private final StatusType status;
    private final Object entity;

    JaxRSResponse(StatusType status, Headers headers, Object entity) {
        this.status = status;
        this.headers = headers;
        this.entity = entity;
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
        return null;
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType) {
        return null;
    }

    @Override
    public <T> T readEntity(Class<T> entityType, Annotation[] annotations) {
        return null;
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) {
        return null;
    }

    @Override
    public boolean hasEntity() {
        return false;
    }

    @Override
    public boolean bufferEntity() {
        return false;
    }

    @Override
    public void close() {

    }

    @Override
    public MediaType getMediaType() {
        return null;
    }

    @Override
    public Locale getLanguage() {
        return null;
    }

    @Override
    public int getLength() {
        return 0;
    }

    @Override
    public Set<String> getAllowedMethods() {
        return null;
    }

    @Override
    public Map<String, NewCookie> getCookies() {
        return null;
    }

    @Override
    public EntityTag getEntityTag() {
        return null;
    }

    @Override
    public Date getDate() {
        return null;
    }

    @Override
    public Date getLastModified() {
        return null;
    }

    @Override
    public URI getLocation() {
        return null;
    }

    @Override
    public Set<Link> getLinks() {
        return null;
    }

    @Override
    public boolean hasLink(String relation) {
        return false;
    }

    @Override
    public Link getLink(String relation) {
        return null;
    }

    @Override
    public Link.Builder getLinkBuilder(String relation) {
        return null;
    }

    @Override
    public MultivaluedMap<String, Object> getMetadata() {
        return null;
    }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        return null;
    }

    @Override
    public String getHeaderString(String name) {
        return null;
    }

    public static class Builder extends Response.ResponseBuilder {
        static {
            MuRuntimeDelegate.ensureSet();
        }

        private final Headers headers = new Headers();
        private final List<Link> linkHeaders = new ArrayList<>();
        private StatusType status;
        private Object entity;
        private Annotation[] annotations;
        private NewCookie[] cookies;
        private MediaType type;
        private Variant variant;
        private List<Variant> variants = new ArrayList<>();

        @Override
        public Response build() {
            for (Link linkHeader : linkHeaders) {
                headers.add(HeaderNames.LINK, "<" + linkHeader.getUri().toString() + ">;rel=" + linkHeader.getRel());
            }
            return new JaxRSResponse(status, headers, entity);
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
                return header(HttpHeaders.ALLOW, null);
            }

            StringBuilder allow = new StringBuilder();
            for (String m : methods) {
                append(allow, true, m);
            }
            return header(HttpHeaders.ALLOW, allow.toString());
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
            if (value == null) {
                headers.remove(name);
            } else {
                headers.add(name, value);
            }
            return this;
        }

        @Override
        public ResponseBuilder header(String name, Object value) {
            return header((CharSequence)name, value);
        }

        @Override
        public ResponseBuilder replaceAll(MultivaluedMap<String, Object> headers) {
            this.headers.clear();
            for (Map.Entry<String, List<Object>> entry : headers.entrySet()) {
                this.headers.add(entry.getKey(), entry.getValue());
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
            this.variant = variant;
            return this;
        }

        @Override
        public ResponseBuilder contentLocation(URI location) {
            return header(HeaderNames.CONTENT_LOCATION, location);
        }

        @Override
        public ResponseBuilder cookie(NewCookie... cookies) {
            this.cookies = cookies;
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
            this.variants = variants;
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
            throw NotImplementedException.notYet();
        }

        @Override
        public ResponseBuilder link(String uri, String rel) {
            throw NotImplementedException.notYet();
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
