package io.muserver.rest;

import io.muserver.Method;
import io.muserver.MuRequest;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.*;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.*;

import static java.util.Collections.emptyList;

class MuContainerRequestContext implements ContainerRequestContext {

    final MuRequest muRequest;
    private InputStream inputStream;
    private final String relativePath;
    private final JaxRsHttpHeadersAdapter jaxHeaders;
    private UriInfo uriInfo;
    private RequestMatcher.MatchedMethod matchedMethod;
    private final JaxRequest jaxRequest;
    private SecurityContext securityContext;

    MuContainerRequestContext(MuRequest muRequest, InputStream inputStream, String relativePath, SecurityContext securityContext) {
        this.muRequest = muRequest;
        this.inputStream = inputStream;
        this.relativePath = relativePath;
        this.securityContext = securityContext;
        String basePath = muRequest.contextPath();
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }
        this.uriInfo = RestHandler.createUriInfo(relativePath, null, muRequest.uri().resolve(basePath), muRequest.uri());
        this.jaxRequest = new JaxRequest(muRequest);
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
        return muRequest.attribute(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
        return muRequest.attributes().keySet();
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
        return jaxRequest;
    }

    @Override
    public String getMethod() {
        return jaxRequest.getMethod();
    }

    Method getMuMethod() {
        return Method.valueOf(getMethod());
    }

    @Override
    public void setMethod(String method) {
        if (matchedMethod != null) {
            throw new IllegalStateException("This method is only valid for @PreMatching filters");
        }
        jaxRequest.setMethod(method);
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
}
