package io.muserver.rest;

import io.muserver.CookieBuilder;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.util.List;

class CookieHeaderDelegate implements RuntimeDelegate.HeaderDelegate<Cookie> {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    @Override
    public Cookie fromString(String value) {
        if (value == null) throw new IllegalArgumentException("Cookie value was null");
        List<CookieBuilder> builders = CookieBuilder.fromCookieHeader(value);
        if (builders.isEmpty()) throw new IllegalArgumentException("No cookie value was specified");
        io.muserver.Cookie muc = builders.get(0).build();
        return new Cookie(muc.name(), muc.value());
    }

    @Override
    public String toString(Cookie cookie) {
        io.muserver.Cookie muc = CookieBuilder.newCookie()
            .withName(cookie.getName())
            .withValue(cookie.getValue())
            .build();
        return muc.toString();
    }

}
