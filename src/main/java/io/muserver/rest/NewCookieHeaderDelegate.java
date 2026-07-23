package io.muserver.rest;

import io.muserver.Mutils;
import io.netty.handler.codec.http.cookie.*;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class NewCookieHeaderDelegate implements RuntimeDelegate.HeaderDelegate<NewCookie> {
    private static final Pattern SAME_SITE_ATTRIBUTE =
        Pattern.compile(";\\s*SameSite\\s*=\\s*([^;]*)", Pattern.CASE_INSENSITIVE);

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
        NewCookie.SameSite sameSite = sameSiteFromHeader(value);
        if (sameSite == null && cookie instanceof DefaultCookie) {
            sameSite = fromNetty(((DefaultCookie) cookie).sameSite());
        }

        int maxAge = cookie.maxAge() == DefaultCookie.UNDEFINED_MAX_AGE ? NewCookie.DEFAULT_MAX_AGE : (int) cookie.maxAge();
        return new NewCookie.Builder(cookie.name())
            .value(cookie.value())
            .path(cookie.path())
            .domain(cookie.domain())
            .maxAge(maxAge)
            .secure(cookie.isSecure())
            .httpOnly(cookie.isHttpOnly())
            .sameSite(sameSite)
            .build();
    }

    private static NewCookie.@Nullable SameSite sameSiteFromHeader(String value) {
        Matcher matcher = SAME_SITE_ATTRIBUTE.matcher(value);
        NewCookie.SameSite result = null;
        while (matcher.find()) {
            String sameSite = matcher.group(1).trim();
            try {
                result = NewCookie.SameSite.valueOf(sameSite.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid SameSite value: " + sameSite, e);
            }
        }
        return result;
    }

    private static NewCookie.@Nullable SameSite fromNetty(CookieHeaderNames.@Nullable SameSite nettySameSite) {
        if (nettySameSite == null) {
            return null;
        }
        switch (nettySameSite) {
            case Strict:
                return NewCookie.SameSite.STRICT;
            case Lax:
                return NewCookie.SameSite.LAX;
            case None:
                return NewCookie.SameSite.NONE;
            default:
                throw new IllegalArgumentException("Unknown SameSite value: " + nettySameSite);
        }
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
        var ss = cookie.getSameSite();
        if (ss != null) {
            nettyCookie.setSameSite(toNetty(ss));
        }
        return encoder.encode(nettyCookie);
    }

    private CookieHeaderNames.SameSite toNetty(NewCookie.SameSite ss) {
        switch (ss) {
            case STRICT:
                return CookieHeaderNames.SameSite.Strict;
            case LAX:
                return CookieHeaderNames.SameSite.Lax;
            case NONE:
                return CookieHeaderNames.SameSite.None;
            default:
                throw new IllegalArgumentException("Unknown SameSite value: " + ss);
        }
    }

}
