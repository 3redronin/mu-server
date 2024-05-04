package io.muserver.rest;

import io.muserver.*;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.*;

class JaxRSRequest implements Request, ContainerRequestContext, ReaderInterceptorContext {

    final MuRequest muRequest;
    private final MuResponse muResponse;
    private InputStream inputStream;
    private final String relativePath;
    private final JaxRsHttpHeadersAdapter jaxHeaders;
    private UriInfo uriInfo;
    private RequestMatcher.MatchedMethod matchedMethod;
    private SecurityContext securityContext;
    private Annotation[] annotations = JaxRSResponse.Builder.EMPTY_ANNOTATIONS;
    private Class<?> type;
    private Type genericType;
    private int nextReader;
    private final List<ReaderInterceptor> readerInterceptors;
    private final EntityProviders entityProviders;
    private String httpMethod;

    JaxRSRequest(MuRequest muRequest, MuResponse muResponse, InputStream inputStream, String relativePath, SecurityContext securityContext, List<ReaderInterceptor> readerInterceptors, EntityProviders entityProviders) {
        this.muRequest = muRequest;
        this.httpMethod = muRequest.method().name();
        this.muResponse = muResponse;
        this.inputStream = inputStream;
        this.relativePath = relativePath;
        this.securityContext = securityContext;
        this.readerInterceptors = readerInterceptors;
        this.entityProviders = entityProviders;
        String basePath = muRequest.contextPath();
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }
        this.uriInfo = RestHandler.createUriInfo(relativePath, null, muRequest.uri().resolve(basePath), muRequest.uri());
        this.jaxHeaders = new JaxRsHttpHeadersAdapter(muRequest.headers(), muRequest.cookies());
    }

    boolean methodHasAnnotations(List<Class<? extends Annotation>> toCheck) {
        if (matchedMethod == null) {
            return false;
        }
        return matchedMethod.resourceMethod.hasAll(toCheck);
    }

    @Override
    public Object getProperty(String name) {
        if (MuRuntimeDelegate.MU_REQUEST_PROPERTY.equals(name)) {
            return muRequest;
        } else if (MuRuntimeDelegate.RESOURCE_INFO_PROPERTY.equals(name)) {
            Class<?> resourceInstanceClass = (matchedMethod == null) ? null : matchedMethod.matchedClass.resourceClass.resourceInstance.getClass();
            java.lang.reflect.Method resourceInstanceMethod = (matchedMethod == null) ? null : matchedMethod.resourceMethod.methodHandle;
            return new MuResourceInfo(resourceInstanceClass, resourceInstanceMethod);
        }
        return muRequest.attribute(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
        Set<String> copy = new HashSet<>(muRequest.attributes().keySet());
        copy.add(MuRuntimeDelegate.MU_REQUEST_PROPERTY);
        copy.add(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY);
        return Collections.unmodifiableSet(copy);
    }

    @Override
    public void setProperty(String name, Object object) {
        muRequest.attribute(name, object);
    }

    @Override
    public void removeProperty(String name) {
        muRequest.attribute(name, null);
    }

    @Override
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
    public Class<?> getType() {
        return type;
    }

    @Override
    public void setType(Class<?> type) {
        this.type = type;
    }

    @Override
    public Type getGenericType() {
        return genericType;
    }

    @Override
    public void setGenericType(Type genericType) {
        this.genericType = genericType;
    }

    @Override
    public UriInfo getUriInfo() {
        return uriInfo;
    }

    @Override
    public void setRequestUri(URI requestUri) {
        setRequestUri(uriInfo.getBaseUri(), requestUri);
    }

    @Override
    public void setRequestUri(URI baseUri, URI requestUri) {
        if (matchedMethod != null) {
            throw new IllegalStateException("This method is only valid for @PreMatching filters");
        }
        URI absoluteUri = baseUri.resolve(requestUri);
        this.uriInfo = RestHandler.createUriInfo(baseUri.relativize(requestUri).getRawPath(), null, baseUri, absoluteUri);
    }

    @Override
    public Request getRequest() {
        return this;
    }

    @Override
    public String getMethod() {
        return httpMethod;
    }

    @Override
    public Variant selectVariant(List<Variant> variants) {
        for (Variant variant : variants) {
            if (variant.getMediaType() != null) {
                if (!muResponse.headers().contains(HeaderNames.VARY, HeaderNames.ACCEPT, true)) {
                    muResponse.headers().add(HeaderNames.VARY, HeaderNames.ACCEPT);
                }
            }
            if (variant.getEncoding() != null) {
                if (!muResponse.headers().contains(HeaderNames.VARY, HeaderNames.ACCEPT_ENCODING, true)) {
                    muResponse.headers().add(HeaderNames.VARY, HeaderNames.ACCEPT_ENCODING);
                }
            }
            if (variant.getLanguage() != null) {
                if (!muResponse.headers().contains(HeaderNames.VARY, HeaderNames.ACCEPT_LANGUAGE, true)) {
                    muResponse.headers().add(HeaderNames.VARY, HeaderNames.ACCEPT_LANGUAGE);
                }
            }
        }
        String acceptLang = jaxHeaders.getHeaderString("accept-language");
        List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLang == null ? "*" : acceptLang);
        return MuVariantListBuilder.selectVariant(variants, ranges, getAcceptableMediaTypes(), muRequest.headers().acceptEncoding());
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(EntityTag eTag) {
        if (eTag == null) {
            throw new IllegalArgumentException("eTag is null");
        }
        Response.ResponseBuilder ifMatchBuilder = evaluateIfMatch(eTag);
        return ifMatchBuilder != null ? ifMatchBuilder : evaluateIfNoneMatch(eTag);
    }

    private Response.ResponseBuilder evaluateIfMatch(EntityTag eTag) {
        boolean anyMatch = false;
        List<String> ifMatches = muRequest.headers().getAll(HeaderNames.IF_MATCH);
        if (ifMatches.isEmpty()) {
            return null;
        }
        if (eTag.isWeak()) {
            return Response.status(412).entity(new ClientErrorException("Precondition failed: if-match failed due to weak eTag", 412));
        }
        for (String suppliedEtag : ifMatches) {
            EntityTag supplied = EntityTag.valueOf(suppliedEtag);
            if (supplied.equals(eTag)) {
                anyMatch = true;
                break;
            }
        }
        if (anyMatch) {
            return null;
        }
        return Response.status(412).entity(new ClientErrorException("Precondition failed: if-match", 412));
    }


    private Response.ResponseBuilder evaluateIfNoneMatch(EntityTag eTag) {
        List<String> ifNoneMatchTags = muRequest.headers().getAll(HeaderNames.IF_NONE_MATCH);
        boolean getOrHead = isGetOrHead();
        if (!getOrHead && eTag.isWeak()) {
            return Response.status(412).entity(new ClientErrorException("Precondition failed: if-match failed due to weak eTag", 412));
        }

        boolean noneMatch = true;
        for (String suppliedEtag : ifNoneMatchTags) {
            EntityTag supplied = EntityTag.valueOf(suppliedEtag);
            if (supplied.equals(eTag) || (getOrHead && supplied.getValue().equals(eTag.getValue()))) {
                noneMatch = false;
                break;
            }
        }
        if (noneMatch) {
            return null;
        }
        return getOrHead ? Response.status(304).tag(eTag) : Response.status(412).entity(new ClientErrorException("Precondition failed: if-match", 412));
    }

    private boolean isGetOrHead() {
        return muRequest.method() == Method.GET || muRequest.method() == Method.HEAD;
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(Date lastModified) {
        if (lastModified == null) {
            throw new IllegalArgumentException("lastModified is null");
        }
        Response.ResponseBuilder ifUnmodifiedSince = evaluateIfUnmodifiedSince(lastModified);
        return ifUnmodifiedSince != null ? ifUnmodifiedSince : evaluateIfModifiedSince(lastModified);
    }

    private Response.ResponseBuilder evaluateIfModifiedSince(Date lastModified) {
        long lastModifiedSeconds = lastModified.getTime() / 1000;
        Long ifModifiedMillis = muRequest.headers().getTimeMillis(HeaderNames.IF_MODIFIED_SINCE);
        if (ifModifiedMillis == null || lastModifiedSeconds > (ifModifiedMillis / 1000)) {
            return null;
        } else {
            return isGetOrHead() ? Response.notModified() : null;
        }
    }
    private Response.ResponseBuilder evaluateIfUnmodifiedSince(Date lastModified) {
        long lastModifiedSeconds = lastModified.getTime() / 1000;
        Long ifUnmodifiedSince = muRequest.headers().getTimeMillis(HeaderNames.IF_UNMODIFIED_SINCE);
        if (ifUnmodifiedSince == null || lastModifiedSeconds <= (ifUnmodifiedSince / 1000)) {
            return null;
        } else {
            return Response.status(Response.Status.PRECONDITION_FAILED)
                .entity(new ClientErrorException("Failed precondition: if-unmodified-since", 412));
        }
    }


    @Override
    public Response.ResponseBuilder evaluatePreconditions(Date lastModified, EntityTag eTag) {
        if (lastModified == null) {
            throw new IllegalArgumentException("lastModified is null");
        }
        if (eTag == null) {
            throw new IllegalArgumentException("eTag is null");
        }

        Response.ResponseBuilder builder = evaluateIfMatch(eTag);
        if (builder != null) {
            return builder;
        }
        builder = evaluateIfUnmodifiedSince(lastModified);
        if (builder != null) {
            return builder;
        }
        builder = evaluateIfNoneMatch(eTag);
        if (builder != null) {
            return builder;
        }
        builder = evaluateIfModifiedSince(lastModified);
        if (builder != null) {
            builder.tag(eTag);
        }
        return builder;
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions() {
        return muRequest.headers().get(HeaderNames.IF_MATCH) == null ? null
            : Response.status(Response.Status.PRECONDITION_FAILED).entity(new ClientErrorException("Precondition failed: if-match", 412));
    }

    Method getMuMethod() {
        return Method.valueOf(getMethod());
    }

    @Override
    public void setMethod(String method) {
        if (matchedMethod != null) {
            throw new IllegalStateException("This method is only valid for @PreMatching filters");
        }
        Mutils.notNull("method", method);
        String upper = method.toUpperCase();
        this.httpMethod = Method.valueOf(upper).name();
    }

    Object executeInterceptors() throws IOException {
        this.nextReader = 0;
        return proceed();
    }

    @Override
    public Object proceed() throws IOException, WebApplicationException {
        if (nextReader < readerInterceptors.size()) {
            nextReader++;
            ReaderInterceptor nextInterceptor = readerInterceptors.get(nextReader - 1);
            List<Class<? extends Annotation>> filterBindings = ResourceClass.getNameBindingAnnotations(nextInterceptor.getClass());
            if (methodHasAnnotations(filterBindings)) {
                return nextInterceptor.aroundReadFrom(this);
            }
        }

        // read the entity
        // Section 4.2.1 - determine message body reader

        // 1. Obtain the media type of the request. If the request does not contain a Content-Type header then use application/octet-stream
        MediaType requestBodyMediaType = getMediaType();
        if (requestBodyMediaType == null) {
            requestBodyMediaType = MediaType.APPLICATION_OCTET_STREAM_TYPE;
        }

        // 2. Identify the Java type of the parameter whose value will be mapped from the entity body.
        Class<?> type = getType();
        Type genericType = getGenericType();
        Annotation[] annotations = getAnnotations();

        // 3 & 4: Select a reader that supports the media type of the request and isReadable
        MessageBodyReader messageBodyReader = entityProviders.selectReader(type, genericType, annotations, requestBodyMediaType);
        try {
            return messageBodyReader.readFrom(type, genericType, annotations, requestBodyMediaType, getHeaders(), getInputStream());
        } catch (NoContentException nce) {
            throw new BadRequestException("No request body was sent", nce);
        }
    }

    @Override
    public InputStream getInputStream() {
        return getEntityStream();
    }

    @Override
    public void setInputStream(InputStream is) {
        setEntityStream(is);
    }

    @Override
    public MultivaluedMap<String, String> getHeaders() {
        return jaxHeaders.getMutableRequestHeaders();
    }

    @Override
    public String getHeaderString(String name) {
        return jaxHeaders.getHeaderString(name);
    }

    @Override
    public Date getDate() {
        return jaxHeaders.getDate();
    }

    @Override
    public Locale getLanguage() {
        return jaxHeaders.getLanguage();
    }

    @Override
    public int getLength() {
        return jaxHeaders.getLength();
    }

    @Override
    public MediaType getMediaType() {
        return jaxHeaders.getMediaType();
    }

    @Override
    public void setMediaType(MediaType mediaType) {
        if (mediaType == null) {
            jaxHeaders.getMutableRequestHeaders().remove("content-type");
        } else {
            jaxHeaders.getMutableRequestHeaders().putSingle("content-type", MediaTypeParser.toString(mediaType));
        }
    }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
        return jaxHeaders.getAcceptableMediaTypes();
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
        return jaxHeaders.getAcceptableLanguages();
    }

    @Override
    public Map<String, Cookie> getCookies() {
        return jaxHeaders.getCookies();
    }

    @Override
    public boolean hasEntity() {
        return muRequest.headers().hasBody();
    }

    @Override
    public InputStream getEntityStream() {
        return inputStream;
    }

    @Override
    public void setEntityStream(InputStream input) {
        this.inputStream = input;
    }

    @Override
    public SecurityContext getSecurityContext() {
        return securityContext;
    }

    @Override
    public void setSecurityContext(SecurityContext context) {
        this.securityContext = context;
    }

    @Override
    public void abortWith(Response response) {
        throw new FilterAbortedException(response);
    }

    void setMatchedMethod(RequestMatcher.MatchedMethod matchedMethod) {
        this.matchedMethod = matchedMethod;
        this.uriInfo = RestHandler.createUriInfo(relativePath, matchedMethod, uriInfo.getBaseUri(), uriInfo.getRequestUri());
    }

    @Override
    public String toString() {
        return getMethod() + " " + uriInfo;
    }

    String relativePath() {
        return getUriInfo().getPath(false);
    }

    /**
     * This special exception doesn't get mapped by an exception mapper, so that the {@link #abortWith(Response)} method
     * can just return the Response passed to it without mapping it.
     */
    static class FilterAbortedException extends WebApplicationException {
        public FilterAbortedException(Response response) {
            super(response);
        }
    }

    private static class MuResourceInfo implements ResourceInfo {

        private final Class<?> clazz;
        private final java.lang.reflect.Method method;

        private MuResourceInfo(Class<?> clazz, java.lang.reflect.Method method) {
            this.clazz = clazz;
            this.method = method;
        }

        @Override
        public java.lang.reflect.Method getResourceMethod() {
            return method;
        }

        @Override
        public Class<?> getResourceClass() {
            return clazz;
        }
    }
}
