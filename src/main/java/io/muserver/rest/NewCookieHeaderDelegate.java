package io.muserver.rest;

import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.muserver.Mutils;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.ext.RuntimeDelegate;

class NewCookieHeaderDelegate implements RuntimeDelegate.HeaderDelegate<NewCookie> {
    static {
        MuRuntimeDelegate.ensureSet();
    }
    private final ServerCookieEncoder encoder = ServerCookieEncoder.LAX;
    private final ClientCookieDecoder decoder = ClientCookieDecoder.LAX;

    @Override
    public NewCookie fromString(String value) {
        Mutils.notNull("value", value);
        Cookie cookie = decoder.decode(value);
        if (cookie == null) {
            throw new IllegalArgumentException("Could not parse cookie header value: " + value);
        }
        int maxAge = cookie.maxAge() == DefaultCookie.UNDEFINED_MAX_AGE ? NewCookie.DEFAULT_MAX_AGE : (int) cookie.maxAge();
        return new NewCookie(cookie.name(), cookie.value(), cookie.path(), cookie.domain(), null, maxAge, cookie.isSecure(), cookie.isHttpOnly());
    }

    @Override
    public String toString(NewCookie cookie) {
        Mutils.notNull("cookie", cookie);
        DefaultCookie nettyCookie = new DefaultCookie(cookie.getName(), cookie.getValue());
        if (cookie.getDomain() != null) {
            nettyCookie.setDomain(cookie.getDomain());
        }
        nettyCookie.setHttpOnly(cookie.isHttpOnly());
        if (cookie.getMaxAge() != NewCookie.DEFAULT_MAX_AGE) {
            nettyCookie.setMaxAge(cookie.getMaxAge());
        }
        if (cookie.getPath() != null) {
            nettyCookie.setPath(cookie.getPath());
        }
        nettyCookie.setSecure(cookie.isSecure());
        return encoder.encode(nettyCookie);
    }
}
