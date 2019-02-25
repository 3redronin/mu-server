package io.muserver;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;

import javax.net.ssl.SSLContext;
import java.util.concurrent.atomic.AtomicReference;

class SslContextProvider {

    private final AtomicReference<SslContext> nettySslContext = new AtomicReference<>();

    public SslContextProvider(SSLContext context) {
        set(context);
    }

    public SslContext get() {
        return nettySslContext.get();
    }

    public void set(SSLContext newValue) {
        JdkSslContext sslContext = new JdkSslContext(newValue, false, ClientAuth.NONE);
        nettySslContext.set(sslContext);
    }
}
