package io.muserver.rest;

import io.muserver.MediaTypeParser;
import io.muserver.Method;
import io.muserver.MuRequest;
import io.muserver.Mutils;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.*;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.ReaderInterceptorContext;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.*;

import static java.util.Collections.emptyList;

class MuContainerRequestContext implements Request, ContainerRequestContext, ReaderInterceptorContext {

    final MuRequest muRequest;
    private InputStream inputStream;
    private final String relativePath;
    private final JaxRsHttpHeadersAdapter jaxHeaders;
    private UriInfo uriInfo;
    private RequestMatcher.MatchedMethod matchedMethod;
    private SecurityContext securityContext;
    private Annotation[] annotations = new Annotation[0];
    private Class<?> type;
    private Type genericType;
    private int nextReader;
    private final List<ReaderInterceptor> readerInterceptors;
    private final EntityProviders entityProviders;
    private String httpMethod;

    MuContainerRequestContext(MuRequest muRequest, InputStream inputStream, String relativePath, SecurityContext securityContext, List<ReaderInterceptor> readerInterceptors, EntityProviders entityProviders) {
        this.muRequest = muRequest;
        this.httpMethod = muRequest.method().name();
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
        if (matchedMethod != null) {
            throw new IllegalStateException("This method is only valid for @PreMatching filters");
        }
        setRequestUri(uriInfo.getBaseUri(), requestUri);
    }

    @Override
    public void setRequestUri(URI baseUri, URI requestUri) {
        if (matchedMethod != null) {
            throw new IllegalStateException("This method is only valid for @PreMatching filters");
        }
        URI fullRequestUri = baseUri.resolve(requestUri);
        uriInfo = new MuUriInfo(baseUri, fullRequestUri, requestUri.getRawPath(), emptyList(), emptyList());
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
        List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(jaxHeaders.getHeaderString("accept-language"));
        return MuVariantListBuilder.selectVariant(variants, ranges, getAcceptableMediaTypes(), muRequest.headers().acceptEncoding());
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(EntityTag eTag) {
        throw NotImplementedException.notYet();
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(Date lastModified) {
        throw NotImplementedException.notYet();
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(Date lastModified, EntityTag eTag) {
        throw NotImplementedException.notYet();
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions() {
        throw NotImplementedException.notYet();
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
        return jaxHeaders.getRequestHeaders();
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
            jaxHeaders.getRequestHeaders().remove("content-type");
        } else {
            jaxHeaders.getRequestHeaders().putSingle("content-type", MediaTypeParser.toString(mediaType));
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
