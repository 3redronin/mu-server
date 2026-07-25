package io.muserver.rest;

import io.muserver.Cookie;
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
        int maxAge = muCookie.maxAge() == null ? NewCookie.DEFAULT_MAX_AGE : muCookie.maxAge().intValue();
        NewCookie.Builder newCookie = new NewCookie.Builder(muCookie.name())
            .value(muCookie.value())
            .path(muCookie.path())
            .domain(muCookie.domain())
            .maxAge(maxAge)
            .secure(muCookie.isSecure())
            .httpOnly(muCookie.isHttpOnly());
        if (muCookie.sameSite() != null) {
            newCookie.sameSite(NewCookie.SameSite.valueOf(muCookie.sameSite().name()));
        }
        return newCookie.build();
    }

    @Override
    public String toString(NewCookie cookie) {
        if (cookie == null) throw new IllegalArgumentException("Cookie value was null");
        Long maxAge = cookie.getMaxAge() == NewCookie.DEFAULT_MAX_AGE ? null : (long) cookie.getMaxAge();
        CookieBuilder builder = CookieBuilder.newCookie()
            .withName(cookie.getName())
            .withValue(cookie.getValue())
            .withPath(cookie.getPath())
            .withDomain(cookie.getDomain())
            .secure(cookie.isSecure())
            .httpOnly(cookie.isHttpOnly())
            .withMaxAgeInSeconds(maxAge);
        if (cookie.getSameSite() != null) {
            builder.withSameSite(Cookie.SameSite.valueOf(cookie.getSameSite().name()));
        }
        return builder.build().toString();
    }

}
