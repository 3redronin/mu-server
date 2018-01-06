package ronin.muserver.rest;

import ronin.muserver.MuException;

import javax.ws.rs.core.*;
import javax.ws.rs.ext.RuntimeDelegate;
import java.util.HashMap;
import java.util.Map;

class MuRuntimeDelegate extends RuntimeDelegate {

    private static MuRuntimeDelegate singleton;
    public static synchronized void ensureSet() {
        if (singleton == null) {
            singleton = new MuRuntimeDelegate();
            RuntimeDelegate.setInstance(singleton);
        }
    }

    private final Map<Class<?>, HeaderDelegate> headerDelegates = new HashMap<>();

    private MuRuntimeDelegate() {
        headerDelegates.put(MediaType.class, new MediaTypeHeaderDelegate());
        headerDelegates.put(CacheControl.class, new CacheControlHeaderDelegate());
        headerDelegates.put(NewCookie.class, new NewCookieHeaderDelegate());
        headerDelegates.put(Cookie.class, new CookieHeaderDelegate());
        headerDelegates.put(EntityTag.class, new EntityTagDelegate());
    }

    @Override
    public UriBuilder createUriBuilder() {
        throw NotImplementedException.notYet();
    }

    @Override
    public Response.ResponseBuilder createResponseBuilder() {
        return new JaxRSResponse.Builder();
    }

    @Override
    public Variant.VariantListBuilder createVariantListBuilder() {
        throw NotImplementedException.notYet();
    }

    @Override
    public <T> T createEndpoint(Application application, Class<T> endpointType) throws IllegalArgumentException, UnsupportedOperationException {
        throw new MuException("MuServer does not support instantiation of application classes");
    }

    @Override
    public <T> HeaderDelegate<T> createHeaderDelegate(Class<T> type) throws IllegalArgumentException {
        HeaderDelegate headerDelegate = headerDelegates.get(type);
        if (headerDelegate != null) {
            return (HeaderDelegate<T>) headerDelegate;
        }
        throw new MuException("MuServer does not support converting " + type.getName());
    }

    @Override
    public Link.Builder createLinkBuilder() {
        throw NotImplementedException.notYet();
    }
}
