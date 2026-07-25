package io.muserver.rest;

import io.muserver.*;
import io.netty.handler.codec.http.HttpHeaderNames;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.RuntimeDelegate;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.jspecify.annotations.Nullable;

import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toMap;

class JaxRSResponse extends Response implements ContainerResponseContext, WriterInterceptorContext {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    private final MultivaluedMap<String, Object> headers;
    private StatusType status;
    private ObjWithType objWithType;
    private final NewCookie[] cookies;
    private final List<Link> links;
    private Annotation[] annotations;
    private @Nullable OutputStream outputStream;

    private @Nullable JaxRSRequest requestContext;
    private @Nullable List<WriterInterceptor> writerInterceptors;
    private int nextWriter = 0;

    private boolean isClosed = false;

    private @Nullable ByteArrayInputStream inputStreamBuffer;

    JaxRSResponse(StatusType status, MultivaluedMap<String, Object> headers, ObjWithType entity, NewCookie[] cookies, List<Link> links, Annotation[] annotations) {
        this.status = status;
        this.headers = headers;
        this.objWithType = entity;
        this.cookies = cookies;
        this.links = links;
        this.annotations = annotations;
    }

    static JaxRSResponse from(Response response) {
        if (response instanceof JaxRSResponse) {
            return (JaxRSResponse) response;
        }
        MultivaluedMap<String, Object> headers = new LowercasedMultivaluedHashMap<>();
        headers.putAll(response.getMetadata());
        NewCookie[] cookies = headers.containsKey(HttpHeaders.SET_COOKIE)
            ? new NewCookie[0]
            : response.getCookies().values().toArray(new NewCookie[0]);
        return new JaxRSResponse(
            response.getStatusInfo(),
            headers,
            ObjWithType.objType(response.getEntity()),
            cookies,
            new ArrayList<>(response.getLinks()),
            Builder.EMPTY_ANNOTATIONS
        );
    }

    public Annotation[] getAnnotations() {
        return annotations;
    }

    @Override
    public void setAnnotations(Annotation[] annotations) {
        if (annotations == null) {
            throw new NullPointerException("The 'annotations' parameter must not be null");
        }
        this.annotations = annotations;
    }

    @Override
    public @Nullable Class<?> getType() {
        return objWithType.type;
    }

    @Override
    public void setType(@Nullable Class<?> type) {
        objWithType = new ObjWithType(type, objWithType.genericType, objWithType.response, objWithType.entity);
    }

    @Override
    public @Nullable Type getGenericType() {
        return objWithType.genericType;
    }

    @Override
    public void setGenericType(@Nullable Type genericType) {
        objWithType = new ObjWithType(objWithType.type, genericType, objWithType.response, objWithType.entity);
    }

    @Override
    public int getStatus() {
        return status.getStatusCode();
    }

    @Override
    public void setStatus(int code) {
        if (code < 100 || code > 599) {
            throw new IllegalArgumentException("Status must be between 100 and 599, but was " + code);
        }
        Status standardStatus = Status.fromStatusCode(code);
        this.status = standardStatus == null
            ? new CustomStatus(Status.Family.familyOf(code), code, "")
            : standardStatus;
    }

    @Override
    public StatusType getStatusInfo() {
        return status;
    }

    @Override
    public void setStatusInfo(StatusType statusInfo) {
        this.status = Objects.requireNonNull(statusInfo, "statusInfo");
    }

    @Override
    public @Nullable Object getEntity() {
        return objWithType.entity;
    }

    @Override
    public @Nullable Class<?> getEntityClass() {
        return getType();
    }

    @Override
    public @Nullable Type getEntityType() {
        return getGenericType();
    }

    @Override
    public void setEntity(@Nullable Object entity) {
        objWithType = ObjWithType.objType(entity);
        if (entity instanceof Response) {
            Response resp = (Response) entity;
            setStatusInfo(resp.getStatusInfo());
        }
    }

    @Override
    public OutputStream getOutputStream() {
        return requiredOutputStream();
    }

    @Override
    public void setOutputStream(OutputStream os) {
        this.outputStream = os;
    }

    @Override
    public void setEntity(@Nullable Object entity, Annotation @Nullable [] annotations, @Nullable MediaType mediaType) {
        setEntity(entity);
        setAnnotations(annotations == null ? Builder.EMPTY_ANNOTATIONS : annotations);
        setMediaType(mediaType);
    }

    @Override
    public Annotation[] getEntityAnnotations() {
        return getAnnotations();
    }

    @Override
    public OutputStream getEntityStream() {
        return requiredOutputStream();
    }

    @Override
    public void setEntityStream(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    @Override
    public <T> T readEntity(Class<T> entityType) {
        return readEntity0(entityType, null, new Annotation[0]);
    }

    private <T> T readEntity0(Class<T> entityType, @Nullable Type genericType, Annotation[] annotations) {
        Object entity = Objects.requireNonNull(getEntity(), "The response has no entity");
        if (inputStreamBuffer == null && !(entity instanceof InputStream)) {
            throw new IllegalStateException("The entity is not an input stream, it is " + entity.getClass());
        }
        if (isClosed) throw new IllegalStateException("Cannot read entity; the response is closed");
        InputStream toRead = inputStreamBuffer != null ? inputStreamBuffer : (InputStream) entity;
        Type typeToRead = genericType == null ? entityType : genericType;
        MediaType mediaType = getMediaType() == null ? MediaType.APPLICATION_OCTET_STREAM_TYPE : getMediaType();
        EntityProviders ep = new EntityProviders(EntityProviders.builtInReaders(), emptyList());
        MessageBodyReader<T> reader = (MessageBodyReader<T>) ep.selectReader(entityType, typeToRead, annotations, mediaType);
        if (reader == null) {
            throw new ProcessingException("Cannot read this entity type");
        }
        try {
            T result = reader.readFrom(entityType, typeToRead, annotations, mediaType, getStringHeaders(), toRead);
            if (inputStreamBuffer != null) {
                inputStreamBuffer.reset();
            } else {
                toRead.close();
            }

            setEntity(result);
            return result;
        } catch (IOException e) {
            throw new ProcessingException("Error while reading entity input stream", e);
        }
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType) {
        return (T) readEntity0(entityType.getRawType(), entityType.getType(), new Annotation[0]);
    }

    @Override
    public <T> T readEntity(Class<T> entityType, Annotation[] annotations) {
        return readEntity0(entityType, null, annotations);
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) {
        return (T) readEntity0(entityType.getRawType(), entityType.getType(), annotations);
    }

    @Override
    public boolean hasEntity() {
        return objWithType.entity != null;
    }

    @Override
    public boolean bufferEntity() {
        if (inputStreamBuffer != null) {
            return true;
        }
        Object entity = getEntity();
        if (entity instanceof InputStream) {
            try (InputStream in = (InputStream)entity;
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                Mutils.copy(in, baos, 8192);
                inputStreamBuffer = new ByteArrayInputStream(baos.toByteArray());
                setEntity(inputStreamBuffer);
            } catch (IOException e) {
                throw new ProcessingException("Could not buffer entity input stream", e);
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void close() {
        if (!isClosed) {
            isClosed = true;
            if (inputStreamBuffer != null) {
                try {
                    inputStreamBuffer.close();
                } catch (IOException e) {
                    throw new ProcessingException("Error while closing entity input stream", e);
                }
                inputStreamBuffer = null;
            }
            Object entity = getEntity();
            if (entity instanceof InputStream) {
                try {
                    ((InputStream) entity).close();
                } catch (IOException e) {
                    throw new ProcessingException("Could not close input stream entity", e);
                }
            }
        }
    }

    @Override
    public @Nullable MediaType getMediaType() {
        String h = getHeaderString("content-type");
        return h == null ? null : MediaTypeParser.fromString(h);
    }

    @Override
    public void setMediaType(@Nullable MediaType mediaType) {
        if (mediaType == null) {
            headers.remove("content-type");
        } else {
            headers.putSingle("content-type", MediaTypeParser.toString(mediaType));
        }
    }

    @Override
    public @Nullable Locale getLanguage() {
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
    public @Nullable EntityTag getEntityTag() {
        Object first = headers.getFirst(HeaderNames.ETAG.toString());
        if (first == null || first instanceof  EntityTag) return (EntityTag)first;
        return EntityTag.valueOf(first.toString());
    }

    @Override
    public @Nullable Date getDate() {
        return dateFromHeader("date");
    }

    private @Nullable Date dateFromHeader(String name) {
        Object date = headers.getFirst(name);
        if (date == null || date.getClass().isAssignableFrom(Date.class)) return (Date)date;
        return Mutils.fromHttpDate(date.toString());
    }

    @Override
    public @Nullable Date getLastModified() {
        return dateFromHeader("last-modified");
    }

    @Override
    public @Nullable URI getLocation() {
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
    public @Nullable Link getLink(String relation) {
        return links.stream().filter(link -> link.getRels().contains(relation)).findFirst().orElse(null);
    }

    @Override
    public Link.@Nullable Builder getLinkBuilder(String relation) {
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
        MultivaluedMap<String, String> map = new LowercasedMultivaluedHashMap<>();
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
        MultivaluedMap<String, Object> map = new LowercasedMultivaluedHashMap<>();
        for (String name : headers.names()) {
            map.addAll(name, headers.getAll(name));
        }
        return map;
    }

    @Override
    public @Nullable String getHeaderString(String name) {
        return headerValueToString(headers.getFirst(name));
    }

    private static @Nullable String headerValueToString(@Nullable Object value) {
        if (value == null || value instanceof String) {
            return (String)value;
        }
        try {
            RuntimeDelegate.HeaderDelegate headerDelegate = MuRuntimeDelegate.getInstance().createHeaderDelegate(value.getClass());
            return headerDelegate.toString(value);
        } catch (MuException e) {
            return value.toString();
        }
    }


    // Start interceptor specific things

    void executeInterceptors(List<WriterInterceptor> writerInterceptors) throws IOException {
        this.nextWriter = 0;
        this.writerInterceptors = writerInterceptors;
        proceed();
    }

    @Override
    public void proceed() throws IOException, WebApplicationException {
        List<WriterInterceptor> interceptors = Objects.requireNonNull(writerInterceptors, "Writer interceptors have not been initialized");
        while (nextWriter < interceptors.size()) {
            nextWriter++;
            WriterInterceptor nextInterceptor = interceptors.get(nextWriter - 1);
            List<Class<? extends Annotation>> filterBindings = ResourceClass.getNameBindingAnnotations(nextInterceptor.getClass());
            if (requiredRequestContext().methodHasAnnotations(filterBindings)) {
                nextInterceptor.aroundWriteTo(this);
                return;
            }
        }
    }

    @Override
    public @Nullable Object getProperty(String name) {
        return requiredRequestContext().getProperty(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
        return requiredRequestContext().getPropertyNames();
    }

    @Override
    public void setProperty(String name, @Nullable Object object) {
        requiredRequestContext().setProperty(name, object);
    }

    @Override
    public void removeProperty(String name) {
        requiredRequestContext().removeProperty(name);
    }

    public void setRequestContext(JaxRSRequest requestContext) {
        this.requestContext = requestContext;
    }

    private OutputStream requiredOutputStream() {
        return Objects.requireNonNull(outputStream, "The response entity stream has not been set");
    }

    private JaxRSRequest requiredRequestContext() {
        return Objects.requireNonNull(requestContext, "The response request context has not been set");
    }

    // End interceptor specific things

    @Override
    public String toString() {
        return getStatusInfo().toString();
    }

    public static class Builder extends Response.ResponseBuilder {
        static {
            MuRuntimeDelegate.ensureSet();
        }
        static final Annotation[] EMPTY_ANNOTATIONS = new Annotation[0];

        private final MultivaluedMap<String, Object> headers = new LowercasedMultivaluedHashMap<>();
        private final List<Link> linkHeaders = new ArrayList<>();
        private @Nullable StatusType status;
        private @Nullable Object entity;
        private Annotation[] annotations = EMPTY_ANNOTATIONS;
        private NewCookie[] cookies = new NewCookie[0];
        private @Nullable MediaType type;

        @Override
        public Response build() {
            if (this.status == null) {
                this.status = Status.fromStatusCode(entity == null ? 204 : 200);
            }
            for (Link linkHeader : linkHeaders) {
                headers.add(HeaderNames.LINK.toString(), linkHeader.toString());
            }
            if (this.type != null) {
                headers.putSingle(HeaderNames.CONTENT_TYPE.toString(), this.type.toString());
            }
            return new JaxRSResponse(Objects.requireNonNull(status), headers, ObjWithType.objType(entity), cookies, linkHeaders, annotations);
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
        public ResponseBuilder status(int code, @Nullable String reasonPhrase) {
            if (code < 100 || code > 599) {
                throw new IllegalArgumentException("Status must be between 100 and 599, but was " + code);
            }
            this.status = Status.fromStatusCode(code);
            if (this.status == null || reasonPhrase != null) {
                this.status = new CustomStatus(Status.Family.familyOf(code), code, reasonPhrase == null ? "" : reasonPhrase);
            }
            return this;
        }

        @Override
        public ResponseBuilder entity(@Nullable Object entity) {
            return entity(entity, EMPTY_ANNOTATIONS);
        }

        @Override
        public ResponseBuilder entity(@Nullable Object entity, Annotation[] annotations) {
            this.entity = entity;
            this.annotations = Objects.requireNonNull(annotations, "annotations");
            return this;
        }

        @Override
        public ResponseBuilder allow(String @Nullable ... methods) {
            if (methods == null || (methods.length == 1 && methods[0] == null)) {
                return allow((Set<String>) null);
            } else {
                return allow(new HashSet<>(Arrays.asList(methods)));
            }
        }

        @Override
        public ResponseBuilder allow(@Nullable Set<String> methods) {
            if (methods == null) {
                return setHeader(HttpHeaderNames.ALLOW, null, true);
            }

            StringBuilder allow = new StringBuilder();
            for (String m : methods) {
                append(allow, true, m);
            }
            return setHeader(HttpHeaderNames.ALLOW, allow.toString(), true);
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
        public ResponseBuilder cacheControl(@Nullable CacheControl cacheControl) {
            return setHeader(HeaderNames.CACHE_CONTROL, cacheControl == null ? null : cacheControl.toString(), false);
        }

        @Override
        public ResponseBuilder encoding(@Nullable String encoding) {
            return setHeader(HeaderNames.CONTENT_ENCODING, encoding, false);
        }

        private ResponseBuilder setHeader(CharSequence name, @Nullable Object value, boolean append) {
            if (value instanceof Iterable) {
                ((Iterable) value).forEach(v -> setHeader(name, v, append));
            } else {
                if (value == null) {
                    headers.remove(name.toString());
                } else {
                    if (append) {
                        headers.add(name.toString(), value);
                    } else {
                        headers.putSingle(name.toString(), value);
                    }
                }
            }
            return this;
        }

        @Override
        public ResponseBuilder header(String name, @Nullable Object value) {
            return setHeader(name, value, true); // TODO should this actually be false?
        }

        @Override
        public ResponseBuilder replaceAll(@Nullable MultivaluedMap<String, Object> headers) {
            this.headers.clear();
            if (headers != null) {
                for (Map.Entry<String, List<Object>> entry : headers.entrySet()) {
                    this.headers.add(entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        @Override
        public ResponseBuilder language(@Nullable String language) {
            return setHeader(HeaderNames.CONTENT_LANGUAGE, language, false);
        }

        @Override
        public ResponseBuilder language(@Nullable Locale language) {
            return language(language == null ? null : language.toLanguageTag());
        }

        @Override
        public ResponseBuilder type(@Nullable MediaType type) {
            this.type = type;
            return this;
        }

        @Override
        public ResponseBuilder type(@Nullable String type) {
            if (type == null) {
                this.type = null;
                return this;
            }
            return type(MediaType.valueOf(type));
        }

        @Override
        public ResponseBuilder variant(@Nullable Variant variant) {
            language(variant == null ? null : variant.getLanguage());
            type(variant == null ? null : variant.getMediaType());
            encoding(variant == null ? null : variant.getEncoding());
            return this;
        }

        @Override
        public ResponseBuilder contentLocation(@Nullable URI location) {
            return setHeader(HeaderNames.CONTENT_LOCATION, location, false);
        }

        @Override
        public ResponseBuilder cookie(NewCookie @Nullable ... cookies) {
            this.cookies = cookies == null ? new NewCookie[0] : cookies;
            if (cookies == null) {
                headers.remove(HeaderNames.SET_COOKIE.toString());
            }
            return this;
        }

        @Override
        public ResponseBuilder expires(@Nullable Date expires) {
            return setHeader(HeaderNames.EXPIRES, expires, false);
        }

        @Override
        public ResponseBuilder lastModified(@Nullable Date lastModified) {
            return setHeader(HeaderNames.LAST_MODIFIED, lastModified, false);
        }

        @Override
        public ResponseBuilder location(@Nullable URI location) {
            return setHeader(HeaderNames.LOCATION, location, false);
        }

        @Override
        public ResponseBuilder tag(@Nullable EntityTag tag) {
            return setHeader(HeaderNames.ETAG, tag, false);
        }

        @Override
        public ResponseBuilder tag(@Nullable String tag) {
            return tag == null ? tag((EntityTag) null) : tag(new EntityTag(tag));
        }

        @Override
        public ResponseBuilder variants(Variant @Nullable ... variants) {
            return variants(variants == null ? null : asList(variants));
        }

        @Override
        public ResponseBuilder variants(@Nullable List<Variant> variants) {
            if (variants == null) {
                this.headers.remove("vary");
                return this;
            }
            for (Variant variant : variants) {
                if (variant == null) {
                    this.headers.remove("vary");
                } else {
                    List<Object> existing = this.headers.get("vary");
                    if (existing == null) {
                        existing = emptyList();
                    }
                    if (variant.getMediaType() != null && !existing.contains("content-type")) {
                        this.headers.add("vary", "content-type");
                    }
                    if (variant.getLanguage() != null && !existing.contains("content-language")) {
                        this.headers.add("vary", "content-language");
                    }
                    if (variant.getEncoding() != null && !existing.contains("content-encoding")) {
                        this.headers.add("vary", "content-encoding");
                    }
                }
            }
            return this;
        }

        @Override
        public ResponseBuilder links(Link @Nullable ... links) {
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

    @Override
    public boolean isClosed() {
        return isClosed;
    }
}
