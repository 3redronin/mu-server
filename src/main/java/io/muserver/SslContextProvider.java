package io.muserver;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.OpenSslContext;
import io.netty.handler.ssl.ReferenceCountedOpenSslContext;
import io.netty.handler.ssl.SslContext;

import javax.net.ssl.SSLEngine;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Arrays.asList;

class SslContextProvider {

    private final AtomicReference<SslContext> nettySslContext = new AtomicReference<>();
    private volatile SSLInfo sslInfo;

    public SslContextProvider(SslContext context) {
        set(context);
    }

    public SslContext get() {
        return nettySslContext.get();
    }

    public void set(SslContext newValue) {
        String provider = (newValue instanceof JdkSslContext)
            ? "JDK"
            : (newValue instanceof OpenSslContext || newValue instanceof ReferenceCountedOpenSslContext)
            ? "OpenSSL"
            : "unknown";
        SSLEngine engine = newValue.newEngine(ByteBufAllocator.DEFAULT);
        List<String> protocols = asList(engine.getEnabledProtocols());
        List<String> ciphers = asList(engine.getEnabledCipherSuites());
        sslInfo = new SSLInfoImpl(provider, protocols, ciphers);
        engine.closeOutbound();
        nettySslContext.set(newValue);
    }

    SSLInfo sslInfo() {
        return sslInfo;
    }
}
