package io.muserver.rest;

import io.muserver.Mutils;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;

import javax.ws.rs.core.NewCookie;
import javax.ws.rs.ext.RuntimeDelegate;

class NewCookieHeaderDelegate implements RuntimeDelegate.HeaderDelegate<NewCookie> {
    static {
        MuRuntimeDelegate.ensureSet();
    }
    private final ServerCookieEncoder encoder = ServerCookieEncoder.STRICT;
    private final ClientCookieDecoder decoder = ClientCookieDecoder.STRICT;

    @Override
    public NewCookie fromString(String value) {
        Cookie cookie = decoder.decode(value);
        return new NewCookie(Mutils.urlDecode(cookie.name()), Mutils.urlDecode(cookie.value()), cookie.path(), cookie.domain(), null, (int)cookie.maxAge(), cookie.isSecure(), cookie.isHttpOnly());
    }

    @Override
    public String toString(NewCookie cookie) {
        DefaultCookie nettyCookie = new DefaultCookie(Mutils.urlEncode(cookie.getName()), Mutils.urlEncode(cookie.getValue()));
        nettyCookie.setDomain(cookie.getDomain());
        nettyCookie.setHttpOnly(cookie.isHttpOnly());
        nettyCookie.setMaxAge(cookie.getMaxAge());
        nettyCookie.setPath(cookie.getPath());
        nettyCookie.setSecure(cookie.isSecure());
        return encoder.encode(nettyCookie);
    }
}
