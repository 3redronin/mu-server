package io.muserver.rest;

import io.muserver.Method;
import jakarta.ws.rs.ext.RuntimeDelegate;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.*;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import static io.muserver.rest.LegacyMuRuntimeDelegate.fromJakarta;
import static io.muserver.rest.LegacyMuRuntimeDelegate.toJakarta;

class LegacyJaxRSRequestAdapter implements javax.ws.rs.core.Request, javax.ws.rs.container.ContainerRequestContext, javax.ws.rs.ext.ReaderInterceptorContext {

    private final JaxRSRequest jaxRSRequest;

    LegacyJaxRSRequestAdapter(JaxRSRequest jaxRSRequest) {
        this.jaxRSRequest = jaxRSRequest;
    }

    boolean methodHasAnnotations(List<Class<? extends Annotation>> toCheck) {
        return jaxRSRequest.methodHasAnnotations(toCheck);
    }

    public Object getProperty(String name) {
        return jaxRSRequest.getProperty(name);
    }

    public Collection<String> getPropertyNames() {
        return jaxRSRequest.getPropertyNames();
    }

    public void setProperty(String name, Object object) {
        jaxRSRequest.setProperty(name, object);
    }

    public void removeProperty(String name) {
        jaxRSRequest.removeProperty(name);
    }

    public Annotation[] getAnnotations() {
        return jaxRSRequest.getAnnotations();
    }

    public void setAnnotations(Annotation[] annotations) {
        jaxRSRequest.setAnnotations(annotations);
    }

    public Class<?> getType() {
        return jaxRSRequest.getType();
    }

    public void setType(Class<?> type) {
        jaxRSRequest.setType(type);
    }

    public Type getGenericType() {
        return jaxRSRequest.getGenericType();
    }

    public void setGenericType(Type genericType) {
        jaxRSRequest.setGenericType(genericType);
    }

    public UriInfo getUriInfo() {
        jakarta.ws.rs.core.UriInfo uri = jaxRSRequest.getUriInfo();
        return new LegacyMuUriInfo(uri.getBaseUri(), uri.getRequestUri(), uri.getPath(false), uri.getMatchedURIs(), uri.getMatchedResources());
    }

    public void setRequestUri(URI requestUri) {
        jaxRSRequest.setRequestUri(requestUri);
    }

    public void setRequestUri(URI baseUri, URI requestUri) {
        jaxRSRequest.setRequestUri(baseUri, requestUri);
    }

    public Request getRequest() {
        return this;
    }

    public String getMethod() {
        return jaxRSRequest.getMethod();
    }

    @Override
    public Variant selectVariant(List<Variant> variants) {
        jakarta.ws.rs.core.Variant selected = jaxRSRequest.selectVariant(variants.stream().map(LegacyMuRuntimeDelegate::toJakarta).collect(Collectors.toList()));
        return selected == null ? null : fromJakarta(selected);
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(EntityTag eTag) {
        jakarta.ws.rs.core.EntityTag converted = toJakarta(eTag);
        return new LegacyJaxRSResponse.Builder((JaxRSResponse.Builder)jaxRSRequest.evaluatePreconditions(converted));
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(Date lastModified) {
        return new LegacyJaxRSResponse.Builder((JaxRSResponse.Builder)jaxRSRequest.evaluatePreconditions(lastModified));
    }


    public Response.ResponseBuilder evaluatePreconditions(Date lastModified, EntityTag eTag) {
        RuntimeDelegate.HeaderDelegate<jakarta.ws.rs.core.EntityTag> headerDelegate = MuRuntimeDelegate.getInstance().createHeaderDelegate(jakarta.ws.rs.core.EntityTag.class);
        jakarta.ws.rs.core.EntityTag newEtag = headerDelegate.fromString(eTag.toString());
        jakarta.ws.rs.core.Response.ResponseBuilder responseBuilder = jaxRSRequest.evaluatePreconditions(lastModified, newEtag);
        return new LegacyJaxRSResponse.Builder((JaxRSResponse.Builder)responseBuilder);
    }

    public Response.ResponseBuilder evaluatePreconditions() {
        return new LegacyJaxRSResponse.Builder((JaxRSResponse.Builder)jaxRSRequest.evaluatePreconditions());
    }

    Method getMuMethod() {
        return jaxRSRequest.getMuMethod();
    }

    public void setMethod(String method) {
        jaxRSRequest.setMethod(method);
    }

    Object executeInterceptors() throws IOException {
        return jaxRSRequest.executeInterceptors();
    }

    public Object proceed() throws IOException, WebApplicationException {
        return jaxRSRequest.proceed();
    }

    public InputStream getInputStream() {
        return jaxRSRequest.getInputStream();
    }

    public void setInputStream(InputStream is) {
        jaxRSRequest.setInputStream(is);
    }

    public MultivaluedMap<String, String> getHeaders() {
        return new LegacyMultivaluedMapAdapter<>(jaxRSRequest.getHeaders());
    }

    public String getHeaderString(String name) {
        return jaxRSRequest.getHeaderString(name);
    }

    public Date getDate() {
        return jaxRSRequest.getDate();
    }

    public Locale getLanguage() {
        return jaxRSRequest.getLanguage();
    }

    public int getLength() {
        return jaxRSRequest.getLength();
    }

    public MediaType getMediaType() {
        return fromJakarta(jaxRSRequest.getMediaType());
    }

    @Override
    public void setMediaType(javax.ws.rs.core.MediaType mediaType) {
        jaxRSRequest.setMediaType(toJakarta(mediaType));
    }

    public List<MediaType> getAcceptableMediaTypes() {
        return jaxRSRequest.getAcceptableMediaTypes().stream().map(LegacyMuRuntimeDelegate::fromJakarta).collect(Collectors.toList());
    }

    public List<Locale> getAcceptableLanguages() {
        return jaxRSRequest.getAcceptableLanguages();
    }

    public Map<String, Cookie> getCookies() {
        Map<String, jakarta.ws.rs.core.Cookie> original = jaxRSRequest.getCookies();
        Map<String, Cookie> copy = new HashMap<>();
        for (Map.Entry<String, jakarta.ws.rs.core.Cookie> entry : original.entrySet()) {
            copy.put(entry.getKey(), fromJakarta(entry.getValue()));
        }
        return copy;
    }

    public boolean hasEntity() {
        return jaxRSRequest.hasEntity();
    }

    public InputStream getEntityStream() {
        return jaxRSRequest.getEntityStream();
    }

    public void setEntityStream(InputStream input) {
        jaxRSRequest.setEntityStream(input);
    }

    public SecurityContext getSecurityContext() {
        return new LegacySecurityContextAdapter(jaxRSRequest.getSecurityContext());
    }

    @Override
    public void setSecurityContext(javax.ws.rs.core.SecurityContext context) {
        jaxRSRequest.setSecurityContext(new LegacySecurityContextAdapterToJakarkta(context));
    }

    @Override
    public void abortWith(Response response) {
        jaxRSRequest.abortWith(((LegacyJaxRSResponse)response).underlying);
    }

    public String toString() {
        return jaxRSRequest.toString();
    }

}
