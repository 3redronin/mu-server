package io.muserver;

import io.netty.handler.ssl.SslContext;

import java.util.concurrent.atomic.AtomicReference;

class SslContextProvider {

    private final AtomicReference<SslContext> nettySslContext = new AtomicReference<>();

    public SslContextProvider(SslContext context) {
        set(context);
    }

    public SslContext get() {
        return nettySslContext.get();
    }

    public void set(SslContext newValue) {
        nettySslContext.set(newValue);
    }
}
