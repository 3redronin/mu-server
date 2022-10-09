package io.muserver.rest;

import io.muserver.MuException;
import io.muserver.Mutils;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.*;
import javax.ws.rs.ext.RuntimeDelegate;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseBroadcaster;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>The JAX-RS runtime delegate for mu-server.</p>
 * @deprecated This is to support the old 'javax' package only.
 */
@Deprecated
public class LegacyMuRuntimeDelegate extends RuntimeDelegate {

    private static final LegacyMuRuntimeDelegate instance = new LegacyMuRuntimeDelegate();

    private final Map<Class<?>, HeaderDelegate> headerDelegates = new HashMap<>();

    LegacyMuRuntimeDelegate() {
        headerDelegates.put(MediaType.class, new LegacyMediaTypeHeaderDelegate());
        headerDelegates.put(CacheControl.class, new LegacyCacheControlHeaderDelegate());
        headerDelegates.put(NewCookie.class, new LegacyNewCookieHeaderDelegate());
        headerDelegates.put(Cookie.class, new LegacyCookieHeaderDelegate());
        headerDelegates.put(EntityTag.class, new LegacyEntityTagDelegate());
        headerDelegates.put(Link.class, new LegacyLinkHeaderDelegate());
    }

    /**
     * @param broadcaster A MuServer SSE broadcaster
     * @return the number of SSE clients currently connected to the broadcaster
     */
    public static int connectedSinksCount(SseBroadcaster broadcaster) {
        Mutils.notNull("broadcaster", broadcaster);
        if (broadcaster instanceof LegacySseBroadcasterImpl) {
            return ((LegacySseBroadcasterImpl) broadcaster).connectedSinksCount();
        } else {
            throw new IllegalArgumentException("The given broadcaster was not created by MuServer. It was of type " + broadcaster.getClass());
        }
    }


    static jakarta.ws.rs.core.MediaType toJakarta(MediaType mediaType) {
        if (mediaType == null) {
            return null;
        }
        return MuRuntimeDelegate.getInstance().createHeaderDelegate(jakarta.ws.rs.core.MediaType.class).fromString(mediaType.toString());
    }

    static jakarta.ws.rs.core.EntityTag toJakarta(EntityTag entityTag) {
        if (entityTag == null) {
            return null;
        }
        return MuRuntimeDelegate.getInstance().createHeaderDelegate(jakarta.ws.rs.core.EntityTag.class).fromString(entityTag.toString());
    }

    static MediaType fromJakarta(jakarta.ws.rs.core.MediaType mediaType) {
        if (mediaType == null) {
            return null;
        }
        return instance.createHeaderDelegate(MediaType.class).fromString(mediaType.toString());
    }

    static jakarta.ws.rs.core.CacheControl toJakarta(CacheControl cacheControl) {
        if (cacheControl == null) {
            return null;
        }
        return MuRuntimeDelegate.getInstance().createHeaderDelegate(jakarta.ws.rs.core.CacheControl.class).fromString(cacheControl.toString());
    }

    static jakarta.ws.rs.core.Variant toJakarta(Variant variant) {
        if (variant == null) {
            return null;
        }
        return new jakarta.ws.rs.core.Variant(toJakarta(variant.getMediaType()), variant.getLanguage(), variant.getEncoding());
    }

    static Variant fromJakarta(jakarta.ws.rs.core.Variant variant) {
        if (variant == null) {
            return null;
        }
        return new Variant(fromJakarta(variant.getMediaType()), variant.getLanguage(), variant.getEncoding());
    }

    static jakarta.ws.rs.core.NewCookie toJakarta(NewCookie cookie) {
        if (cookie == null) {
            return null;
        }
        return MuRuntimeDelegate.getInstance().createHeaderDelegate(jakarta.ws.rs.core.NewCookie.class).fromString(cookie.toString());
    }

    static jakarta.ws.rs.core.Cookie toJakarta(Cookie cookie) {
        if (cookie == null) {
            return null;
        }
        return MuRuntimeDelegate.getInstance().createHeaderDelegate(jakarta.ws.rs.core.Cookie.class).fromString(cookie.toString());
    }

    static Cookie fromJakarta(jakarta.ws.rs.core.Cookie cookie) {
        if (cookie == null) {
            return null;
        }
        return instance.createHeaderDelegate(Cookie.class).fromString(cookie.toString());
    }


    static jakarta.ws.rs.core.Link toJakarta(Link link) {
        if (link == null) {
            return null;
        }
        return MuRuntimeDelegate.getInstance().createHeaderDelegate(jakarta.ws.rs.core.Link.class).fromString(link.toString());
    }

    public static LegacyMuRuntimeDelegate instance() {
        return instance;
    }

    @Override
    public UriBuilder createUriBuilder() {
        return new LegacyMuUriBuilder();
    }

    @Override
    public Response.ResponseBuilder createResponseBuilder() {
        return new LegacyJavaxRSResponse.Builder();
    }

    @Override
    public Variant.VariantListBuilder createVariantListBuilder() {
        return new LegacyMuVariantListBuilder();
    }

    @Override
    public <T> T createEndpoint(Application application, Class<T> endpointType) throws IllegalArgumentException, UnsupportedOperationException {
        throw new MuException("MuServer does not support instantiation of application classes");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HeaderDelegate<T> createHeaderDelegate(Class<T> type) throws IllegalArgumentException {
        HeaderDelegate<T> headerDelegate = headerDelegates.get(type);
        if (headerDelegate != null) {
            return (HeaderDelegate<T>) headerDelegate;
        }
        throw new MuException("MuServer does not support converting " + type.getName());
    }

    @Override
    public Link.Builder createLinkBuilder() {
        return new LegacyLinkHeaderDelegate.MuLinkBuilder();
    }

    /**
     * <p>Creates a new SSE interface that can create SSE Events and Broadcasters.</p>
     * <p>This can be useful if you want to have a singleton broadcaster in your class that you can declare as a final field.</p>
     * @return A broadcaster that can be used to publish SSE events to clients.
     */
    public static Sse createSseFactory() {
        return new LegacyJaxSseImpl();
    }

    /**
     * The {@link ContainerRequestContext} or {@link jakarta.ws.rs.ext.InterceptorContext} property name to use to get the {@link io.muserver.MuRequest} for the current
     * JAX-RS request, which can be used in a {@link jakarta.ws.rs.container.ContainerRequestFilter},
     * {@link jakarta.ws.rs.container.ContainerResponseFilter}, {@link jakarta.ws.rs.ext.ReaderInterceptor} or {@link jakarta.ws.rs.ext.WriterInterceptor}.
     * <p>Example: <code>MuRequest muRequest = (MuRequest) requestContext.getProperty(MuRuntimeDelegate.MU_REQUEST_PROPERTY);</code></p>
     */
    public static final String MU_REQUEST_PROPERTY = "io.muserver.MU_REQUEST";

    /**
     * The {@link ContainerRequestContext} or {@link jakarta.ws.rs.ext.InterceptorContext} property name to use to get the {@link jakarta.ws.rs.container.ResourceInfo} for the current
     * JAX-RS request, which can be used in a {@link jakarta.ws.rs.container.ContainerRequestFilter},
     * {@link jakarta.ws.rs.container.ContainerResponseFilter}, {@link jakarta.ws.rs.ext.ReaderInterceptor} or {@link jakarta.ws.rs.ext.WriterInterceptor}.
     * <p>Example: <code>ResourceInfo resourceInfo = (ResourceInfo) requestContext.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY);</code></p>
     */
    public static final String RESOURCE_INFO_PROPERTY = "io.muserver.RESOURCE_INFO";

}
