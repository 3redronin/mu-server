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
import static java.util.Objects.requireNonNull;

class SslContextProvider {

    private final AtomicReference<SslContext> nettySslContext = new AtomicReference<>();
    private volatile SSLInfo sslInfo;

    SslContextProvider(SslContext context) {
        sslInfo = createSslInfo(context);
        nettySslContext.set(context);
    }

    public SslContext get() {
        return requireNonNull(nettySslContext.get());
    }

    public void set(SslContext newValue) {
        sslInfo = createSslInfo(newValue);
        nettySslContext.set(newValue);
    }

    private static SSLInfo createSslInfo(SslContext context) {
        String provider = (context instanceof JdkSslContext)
            ? "JDK"
            : (context instanceof OpenSslContext || context instanceof ReferenceCountedOpenSslContext)
            ? "OpenSSL"
            : "unknown";
        SSLEngine engine = context.newEngine(ByteBufAllocator.DEFAULT);
        List<String> protocols = asList(engine.getEnabledProtocols());
        List<String> ciphers = asList(engine.getEnabledCipherSuites());
        engine.closeOutbound();
        return new SSLInfoImpl(provider, protocols, ciphers);
    }

    SSLInfo sslInfo() {
        return sslInfo;
    }

}
