package io.muserver.rest;

import io.muserver.CookieBuilder;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.ext.RuntimeDelegate;

class NewCookieHeaderDelegate implements RuntimeDelegate.HeaderDelegate<NewCookie> {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    @Override
    public NewCookie fromString(String value) {
        if (value == null) throw new IllegalArgumentException("Cookie value was null");
        CookieBuilder builder = CookieBuilder.fromSetCookieHeader(value).orElseThrow(() -> new IllegalArgumentException("No cookie value was specified"));
        var muCookie = builder.build();
        int maxAge = muCookie.maxAge() == null ? -1 : muCookie.maxAge().intValue();
        return new NewCookie(muCookie.name(), muCookie.value(), muCookie.path(), muCookie.domain(),
            NewCookie.DEFAULT_VERSION, null, maxAge, null, muCookie.isSecure(), muCookie.isHttpOnly());
    }

    @Override
    public String toString(NewCookie cookie) {
        Long maxAge = cookie.getMaxAge() < 0 ? null : (long)cookie.getMaxAge();
        io.muserver.Cookie muc = CookieBuilder.newCookie()
            .withName(cookie.getName())
            .withValue(cookie.getValue())
            .withPath(cookie.getPath())
            .withDomain(cookie.getDomain())
            .secure(cookie.isSecure())
            .httpOnly(cookie.isHttpOnly())
            .withMaxAgeInSeconds(maxAge)
            .build();
        return muc.toString();
    }
}
