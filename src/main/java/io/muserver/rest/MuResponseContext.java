package io.muserver.rest;

import io.muserver.HeaderNames;

import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.core.*;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

class MuResponseContext implements ContainerResponseContext {

    private final Map<String, NewCookie> cookies;
    private Response.StatusType status;
    private final JaxRSResponse jaxRSResponse;
    private ObjWithType objWithType;
    private Annotation[] entityAnnotations = new Annotation[0];
    private OutputStream outputStream;
    private MediaType mediaType;

    MuResponseContext(JaxRSResponse jaxRSResponse, ObjWithType objWithType, OutputStream outputStream) {
        this.jaxRSResponse = jaxRSResponse;
        this.objWithType = objWithType;
        this.outputStream = outputStream;
        this.cookies = jaxRSResponse.getCookies();
        this.status = jaxRSResponse.getStatusInfo();
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
        return jaxRSResponse.getStringHeaders();
    }

    @Override
    public String getHeaderString(String name) {
        return jaxRSResponse.getHeaderString(name);
    }

    @Override
    public Set<String> getAllowedMethods() {
        return jaxRSResponse.getAllowedMethods();
    }

    @Override
    public Date getDate() {
        return jaxRSResponse.getDate();
    }

    @Override
    public Locale getLanguage() {
        return jaxRSResponse.getLanguage();
    }

    @Override
    public int getLength() {
        return jaxRSResponse.getLength();
    }

    @Override
    public MediaType getMediaType() {
        return mediaType;
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
        return jaxRSResponse.getLocation();
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
        this.mediaType = mediaType;
        if (mediaType == null) {
            jaxRSResponse.getHeaders().remove(HeaderNames.CONTENT_TYPE.toString());
        } else {
            jaxRSResponse.getHeaders().putSingle(HeaderNames.CONTENT_TYPE.toString(), mediaType.toString());
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
