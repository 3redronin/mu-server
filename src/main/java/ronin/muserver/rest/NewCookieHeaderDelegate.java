package ronin.muserver.rest;

import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;

import javax.ws.rs.core.NewCookie;
import javax.ws.rs.ext.RuntimeDelegate;

import static ronin.muserver.Mutils.urlDecode;
import static ronin.muserver.Mutils.urlEncode;

class NewCookieHeaderDelegate implements RuntimeDelegate.HeaderDelegate<NewCookie> {
    static {
        MuRuntimeDelegate.ensureSet();
    }
    private final ServerCookieEncoder encoder = ServerCookieEncoder.STRICT;
    private final ClientCookieDecoder decoder = ClientCookieDecoder.STRICT;

    @Override
    public NewCookie fromString(String value) {
        Cookie cookie = decoder.decode(value);
        return new NewCookie(urlDecode(cookie.name()), urlDecode(cookie.value()), cookie.path(), cookie.domain(), null, (int)cookie.maxAge(), cookie.isSecure(), cookie.isHttpOnly());
    }

    @Override
    public String toString(NewCookie cookie) {
        DefaultCookie nettyCookie = new DefaultCookie(urlEncode(cookie.getName()), urlEncode(cookie.getValue()));
        nettyCookie.setDomain(cookie.getDomain());
        nettyCookie.setHttpOnly(cookie.isHttpOnly());
        nettyCookie.setMaxAge(cookie.getMaxAge());
        nettyCookie.setPath(cookie.getPath());
        nettyCookie.setSecure(cookie.isSecure());
        return encoder.encode(nettyCookie);
    }
}
