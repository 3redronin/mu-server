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

    private final Map<String, Object> props = new HashMap<>();
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
        this.uriInfo = RestHandler.createUriInfo(relativePath, null, muRequest.uri().resolve(muRequest.contextPath() + "/"), muRequest.uri());
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
        return props.get(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
        return props.keySet();
    }

    @Override
    public void setProperty(String name, Object object) {
        props.put(name, object);
    }

    @Override
    public void removeProperty(String name) {
        props.remove(name);
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

    public Method getMuMethod() {
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
        throw new WebApplicationException(response);
    }

    void setMatchedMethod(RequestMatcher.MatchedMethod matchedMethod) {
        this.matchedMethod = matchedMethod;
        this.uriInfo = RestHandler.createUriInfo(relativePath, matchedMethod, uriInfo.getBaseUri(), uriInfo.getRequestUri());
    }

    @Override
    public String toString() {
        return getMethod() + " " + uriInfo;
    }
}
