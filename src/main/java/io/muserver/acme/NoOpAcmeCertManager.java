package io.muserver.acme;

import io.muserver.MuHandler;
import io.muserver.MuServer;
import io.muserver.SSLContextBuilder;

import javax.net.ssl.SSLContext;

class NoOpAcmeCertManager implements AcmeCertManager {
    @Override
    public void start(MuServer muServer) {
    }

    @Override
    public void stop() {
    }

    @Override
    public void forceRenew() {
    }

    @Override
    public SSLContextBuilder createSSLContext() {
        return SSLContextBuilder.unsignedLocalhostCertBuilder();
    }

    @Override
    public MuHandler createHandler() {
        return null;
    }
}
