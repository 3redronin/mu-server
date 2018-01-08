package io.muserver.rest;

import io.muserver.Mutils;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

import javax.ws.rs.core.Cookie;
import javax.ws.rs.ext.RuntimeDelegate;

import java.util.Set;

class CookieHeaderDelegate implements RuntimeDelegate.HeaderDelegate<Cookie> {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    private final ClientCookieEncoder encoder = ClientCookieEncoder.STRICT;
    private final ServerCookieDecoder decoder = ServerCookieDecoder.STRICT;

    @Override
    public Cookie fromString(String value) {
        Set<io.netty.handler.codec.http.cookie.Cookie> decoded = decoder.decode(value);
        io.netty.handler.codec.http.cookie.Cookie nv = decoded.iterator().next();
        return new Cookie(Mutils.urlDecode(nv.name()), Mutils.urlDecode(nv.value()));
    }

    @Override
    public String toString(Cookie cookie) {
        DefaultCookie nettyCookie = new DefaultCookie(Mutils.urlEncode(cookie.getName()), Mutils.urlEncode(cookie.getValue()));
        nettyCookie.setPath(cookie.getPath());
        nettyCookie.setDomain(cookie.getDomain());
        return encoder.encode(nettyCookie);
    }
}
