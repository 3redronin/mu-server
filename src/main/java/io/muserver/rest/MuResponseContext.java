package io.muserver.rest;

import io.muserver.HeaderNames;
import io.muserver.Headers;

import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.core.*;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.*;

class MuResponseContext implements ContainerResponseContext {

    private final Map<String, NewCookie> cookies;
    private final Headers muHeaders;
    private Response.StatusType status;
    private final JaxRSResponse jaxRSResponse;
    private ObjWithType objWithType;
    private Annotation[] entityAnnotations = new Annotation[0];
    private OutputStream outputStream;

    MuResponseContext(JaxRSResponse jaxRSResponse, ObjWithType objWithType, OutputStream outputStream) {
        this.jaxRSResponse = jaxRSResponse;
        this.muHeaders = jaxRSResponse.getMuHeaders();
        this.objWithType = objWithType;
        this.outputStream = outputStream;
        this.cookies = jaxRSResponse.getCookies();
        this.status = jaxRSResponse.getStatusInfo();
    }

    Headers muHeaders() {
        return muHeaders;
    }

    JaxRsHttpHeadersAdapter headersAdapter() {
        return new JaxRsHttpHeadersAdapter(muHeaders, Collections.emptySet());
    }

    @Override
    public int getStatus() {
        return status.getStatusCode();
    }

    @Override
    public void setStatus(int code) {
        status = Response.Status.fromStatusCode(code);
    }

    @Override
    public Response.StatusType getStatusInfo() {
        return status;
    }

    @Override
    public void setStatusInfo(Response.StatusType statusInfo) {
        status = statusInfo;
    }

    @Override
    public MultivaluedMap<String, Object> getHeaders() {
        return jaxRSResponse.getHeaders();
    }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        return JaxRSResponse.muHeadersToJax(muHeaders);
    }

    @Override
    public String getHeaderString(String name) {
        return headersAdapter().getHeaderString(name);
    }

    @Override
    public Set<String> getAllowedMethods() {
        throw NotImplementedException.notYet();
    }

    @Override
    public Date getDate() {
        return headersAdapter().getDate();
    }

    @Override
    public Locale getLanguage() {
        return headersAdapter().getLanguage();
    }

    @Override
    public int getLength() {
        return headersAdapter().getLength();
    }

    @Override
    public MediaType getMediaType() {
        return headersAdapter().getMediaType();
    }

    @Override
    public Map<String, NewCookie> getCookies() {
        return cookies;
    }

    @Override
    public EntityTag getEntityTag() {
        return jaxRSResponse.getEntityTag();
    }

    @Override
    public Date getLastModified() {
        return jaxRSResponse.getLastModified();
    }

    @Override
    public URI getLocation() {
        String s = muHeaders.get(HeaderNames.LOCATION, null);
        return s == null ? null : URI.create(s);
    }

    @Override
    public Set<Link> getLinks() {
        return jaxRSResponse.getLinks();
    }

    @Override
    public boolean hasLink(String relation) {
        return jaxRSResponse.hasLink(relation);
    }

    @Override
    public Link getLink(String relation) {
        return jaxRSResponse.getLink(relation);
    }

    @Override
    public Link.Builder getLinkBuilder(String relation) {
        return jaxRSResponse.getLinkBuilder(relation);
    }

    @Override
    public boolean hasEntity() {
        return objWithType.entity != null;
    }

    @Override
    public Object getEntity() {
        return objWithType.entity;
    }

    @Override
    public Class<?> getEntityClass() {
        return objWithType.type;
    }

    @Override
    public Type getEntityType() {
        return objWithType.genericType;
    }

    @Override
    public void setEntity(Object entity) {
        objWithType = ObjWithType.objType(entity);
    }

    @Override
    public void setEntity(Object entity, Annotation[] annotations, MediaType mediaType) {
        this.objWithType = ObjWithType.objType(entity);
        this.entityAnnotations = annotations;
        if (mediaType == null) {
            muHeaders.remove(HeaderNames.CONTENT_TYPE);
        } else {
            muHeaders.set(HeaderNames.CONTENT_TYPE, mediaType.toString());
        }
    }

    @Override
    public Annotation[] getEntityAnnotations() {
        return entityAnnotations;
    }

    @Override
    public OutputStream getEntityStream() {
        return outputStream;
    }

    @Override
    public void setEntityStream(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    @Override
    public String toString() {
        return getStatusInfo().toString();
    }
}
