package io.muserver.rest;


import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.core.*;
import javax.ws.rs.ext.RuntimeDelegate;
import javax.ws.rs.ext.WriterInterceptorContext;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import static io.muserver.rest.LegacyMuRuntimeDelegate.toJakarta;

class LegacyJaxRSResponse extends Response implements AutoCloseable, ContainerResponseContext, WriterInterceptorContext {

    final JaxRSResponse underlying;

    LegacyJaxRSResponse(JaxRSResponse underlying) {
        this.underlying = underlying;
    }

    public int getStatus() {
        return underlying.getStatus();
    }

    public Response.StatusType getStatusInfo() {
        jakarta.ws.rs.core.Response.StatusType original = underlying.getStatusInfo();
        return Response.Status.fromStatusCode(original.getStatusCode());
    }


    public MediaType getMediaType() {
        RuntimeDelegate.HeaderDelegate<MediaType> headerDelegate = LegacyMuRuntimeDelegate.instance().createHeaderDelegate(MediaType.class);
        return headerDelegate.fromString(underlying.getMediaType().toString());
    }


    public Map<String, NewCookie> getCookies() {
        RuntimeDelegate.HeaderDelegate<NewCookie> headerDelegate = LegacyMuRuntimeDelegate.instance().createHeaderDelegate(NewCookie.class);
        return underlying.getCookies().entrySet().stream().collect(Collectors.toMap(Object::toString, value -> headerDelegate.fromString(value.toString())));
    }

    public EntityTag getEntityTag() {
        jakarta.ws.rs.core.EntityTag entityTag = underlying.getEntityTag();
        if (entityTag == null) return null;
        RuntimeDelegate.HeaderDelegate<EntityTag> headerDelegate = LegacyMuRuntimeDelegate.instance().createHeaderDelegate(EntityTag.class);
        return headerDelegate.fromString(entityTag.toString());
    }


    public Set<Link> getLinks() {
        RuntimeDelegate.HeaderDelegate<Link> headerDelegate = LegacyMuRuntimeDelegate.instance().createHeaderDelegate(Link.class);
        return underlying.getLinks().stream().map(old -> headerDelegate.fromString(old.toString())).collect(Collectors.toSet());
    }

    public Link getLink(String relation) {
        jakarta.ws.rs.core.Link link = underlying.getLink(relation);
        if (link == null) return null;
        RuntimeDelegate.HeaderDelegate<Link> headerDelegate = LegacyMuRuntimeDelegate.instance().createHeaderDelegate(Link.class);
        return headerDelegate.fromString(link.toString());
    }

    public Link.Builder getLinkBuilder(String relation) {
        return new LegacyLinkHeaderDelegate.MuLinkBuilder().link(underlying.getLinkBuilder(relation).build().toString());
    }

    public MultivaluedMap<String, Object> getMetadata() {
        return getHeaders();
    }

    public MultivaluedMap<String, Object> getHeaders() {
        return new LegacyMultivaluedMapAdapter(underlying.getHeaders());
    }

    public MultivaluedMap<String, String> getStringHeaders() {
        return new LegacyMultivaluedMapAdapter<>(underlying.getStringHeaders());
    }

    public Annotation[] getAnnotations() {
        return underlying.getAnnotations();
    }

    public void setAnnotations(Annotation[] annotations) {
        underlying.setAnnotations(annotations);
    }

    public Class<?> getType() {
        return underlying.getType();
    }

    public void setType(Class<?> type) {
        underlying.setType(type);
    }

    public Type getGenericType() {
        return underlying.getGenericType();
    }

    public void setGenericType(Type genericType) {
        underlying.setGenericType(genericType);
    }


    public void setStatus(int code) {
        underlying.setStatus(code);
    }

    public void setStatusInfo(Response.StatusType statusInfo) {
        underlying.setStatusInfo(jakarta.ws.rs.core.Response.Status.fromStatusCode(statusInfo.getStatusCode()));
    }

    public Object getEntity() {
        return underlying.getEntity();
    }

    public Class<?> getEntityClass() {
        return underlying.getEntityClass();
    }

    public Type getEntityType() {
        return underlying.getEntityType();
    }

    public void setEntity(Object entity) {
        underlying.setEntity(entity);
    }

    public OutputStream getOutputStream() {
        return underlying.getOutputStream();
    }

    public void setOutputStream(OutputStream os) {
        underlying.setOutputStream(os);
    }

    public void setEntity(Object entity, Annotation[] annotations, MediaType mediaType) {
        jakarta.ws.rs.core.MediaType jakartaMediaType = toJakarta(mediaType);
        underlying.setEntity(entity, annotations, jakartaMediaType);
    }


    public Annotation[] getEntityAnnotations() {
        return underlying.getEntityAnnotations();
    }

    public OutputStream getEntityStream() {
        return underlying.getEntityStream();
    }

    public void setEntityStream(OutputStream outputStream) {
        underlying.setEntityStream(outputStream);
    }

    public <T> T readEntity(Class<T> entityType) {
        return underlying.readEntity(entityType);
    }

    public <T> T readEntity(GenericType<T> entityType) {
        throw NotImplementedException.willNot();
    }

    public <T> T readEntity(Class<T> entityType, Annotation[] annotations) {
        return underlying.readEntity(entityType, annotations);
    }

    public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) {
        throw NotImplementedException.willNot();
    }

    public boolean hasEntity() {
        return underlying.hasEntity();
    }

    public boolean bufferEntity() {
        return underlying.bufferEntity();
    }

    public void close() {
        underlying.close();
    }


    public void setMediaType(MediaType mediaType) {
        underlying.setMediaType(toJakarta(mediaType));
    }

    public Locale getLanguage() {
        return underlying.getLanguage();
    }

    public int getLength() {
        return underlying.getLength();
    }

    public Set<String> getAllowedMethods() {
        return underlying.getAllowedMethods();
    }


    public Date getDate() {
        return underlying.getDate();
    }

    public Date getLastModified() {
        return underlying.getLastModified();
    }

    public URI getLocation() {
        return underlying.getLocation();
    }

    public boolean hasLink(String relation) {
        return underlying.hasLink(relation);
    }

    public String getHeaderString(String name) {
        return underlying.getHeaderString(name);
    }

    public void proceed() throws IOException, WebApplicationException {
        underlying.proceed();
    }

    public Object getProperty(String name) {
        return underlying.getProperty(name);
    }

    public Collection<String> getPropertyNames() {
        return underlying.getPropertyNames();
    }

    public void setProperty(String name, Object object) {
        underlying.setProperty(name, object);
    }

    public void removeProperty(String name) {
        underlying.removeProperty(name);
    }

    public void setRequestContext(JaxRSRequest requestContext) {
        underlying.setRequestContext(requestContext);
    }

    public String toString() {
        return underlying.toString();
    }


    static class Builder extends Response.ResponseBuilder {

        private final JaxRSResponse.Builder underlying;

        Builder() {
            underlying = new JaxRSResponse.Builder();
        }

        Builder(JaxRSResponse.Builder underlying) {
            this.underlying = underlying;
        }

        @Override
        public Response build() {
            return new LegacyJaxRSResponse((JaxRSResponse) underlying.build());
        }

        @Override
        public Response.ResponseBuilder clone() {
            jakarta.ws.rs.core.Response.ResponseBuilder clone = underlying.clone();
            return new Builder((JaxRSResponse.Builder) clone);
        }

        @Override
        public Response.ResponseBuilder status(int status) {
            underlying.status(status);
            return this;
        }

        @Override
        public Response.ResponseBuilder status(int status, String reasonPhrase) {
            underlying.status(status, reasonPhrase);
            return this;
        }

        @Override
        public Response.ResponseBuilder entity(Object entity) {
            underlying.entity(entity);
            return this;
        }

        @Override
        public Response.ResponseBuilder entity(Object entity, Annotation[] annotations) {
            underlying.entity(entity, annotations);
            return this;
        }

        @Override
        public Response.ResponseBuilder allow(String... methods) {
            underlying.allow(methods);
            return this;
        }

        @Override
        public Response.ResponseBuilder allow(Set<String> methods) {
            underlying.allow(methods);
            return this;
        }

        @Override
        public Response.ResponseBuilder cacheControl(CacheControl cacheControl) {
            underlying.cacheControl(toJakarta(cacheControl));
            return this;
        }

        @Override
        public Response.ResponseBuilder encoding(String encoding) {
            underlying.encoding(encoding);
            return this;
        }

        @Override
        public Response.ResponseBuilder header(String name, Object value) {
            underlying.header(name, value);
            return this;
        }

        @Override
        public Response.ResponseBuilder replaceAll(MultivaluedMap<String, Object> headers) {
            underlying.replaceAll(LegacyMultivaluedMapAdapter.toJakarta(headers));
            return this;
        }

        @Override
        public Response.ResponseBuilder language(String language) {
            underlying.language(language);
            return this;
        }

        @Override
        public Response.ResponseBuilder language(Locale language) {
            underlying.language(language);
            return this;
        }

        @Override
        public Response.ResponseBuilder type(MediaType type) {
            if (type == null) {
                return this;
            }
            return type(type.toString());
        }

        @Override
        public Response.ResponseBuilder type(String type) {
            underlying.type(type);
            return this;
        }

        @Override
        public Response.ResponseBuilder variant(Variant variant) {
            underlying.variant(toJakarta(variant));
            return this;
        }

        @Override
        public Response.ResponseBuilder contentLocation(URI location) {
            underlying.contentLocation(location);
            return this;
        }

        @Override
        public Response.ResponseBuilder cookie(NewCookie... cookies) {
            jakarta.ws.rs.core.NewCookie[] copy = new jakarta.ws.rs.core.NewCookie[cookies.length];
            for (int i = 0; i < copy.length; i++) {
                copy[i] = toJakarta(cookies[i]);
            }
            underlying.cookie(copy);
            return this;
        }

        @Override
        public Response.ResponseBuilder expires(Date expires) {
            underlying.expires(expires);
            return this;
        }

        @Override
        public Response.ResponseBuilder lastModified(Date lastModified) {
            underlying.lastModified(lastModified);
            return this;
        }

        @Override
        public Response.ResponseBuilder location(URI location) {
            underlying.location(location);
            return this;
        }

        @Override
        public Response.ResponseBuilder tag(EntityTag tag) {
            if (tag != null) {
                tag(tag.toString());
            }
            return this;
        }

        @Override
        public Response.ResponseBuilder tag(String tag) {
            underlying.tag(tag);
            return this;
        }

        @Override
        public Response.ResponseBuilder variants(Variant... variants) {
            return variants(Arrays.asList(variants));
        }

        @Override
        public Response.ResponseBuilder variants(List<Variant> variants) {
            underlying.variants(variants.stream().map(LegacyMuRuntimeDelegate::toJakarta).collect(Collectors.toList()));
            return this;
        }

        @Override
        public Response.ResponseBuilder links(Link... links) {
            jakarta.ws.rs.core.Link[] copy = new jakarta.ws.rs.core.Link[links.length];
            for (int i = 0; i < copy.length; i++) {
                copy[i] = toJakarta(links[i]);
            }
            underlying.links(copy);
            return this;
        }

        @Override
        public Response.ResponseBuilder link(URI uri, String rel) {
            underlying.link(uri, rel);
            return this;
        }

        @Override
        public Response.ResponseBuilder link(String uri, String rel) {
            underlying.link(uri, rel);
            return this;
        }
    }

}
