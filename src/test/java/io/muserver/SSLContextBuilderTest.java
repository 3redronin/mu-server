package io.muserver;

import org.junit.Test;

import java.io.File;

public class SSLContextBuilderTest {

    @Test public void canCreateAnUnsignedOne() {
        SSLContextBuilder.unsignedLocalhostCert();
    }

    @Test public void canCreateADefaultOne() {
        SSLContextBuilder.defaultSSLContext();
    }

    @Test(expected = IllegalArgumentException.class)
    public void throwsIfTheClasspathIsInvalid() {
        SSLContextBuilder.sslContext()
            .withKeystoreType("JKS")
            .withKeystorePassword("Very5ecure")
            .withKeyPassword("ActuallyNotSecure")
            .withKeystoreFromClasspath("/io/muserver/resources/wrong.jks")
            .build();
    }

    @Test public void canCreateFromTheFileSystem() {
        SSLContextBuilder.sslContext()
            .withKeystoreType("JKS")
            .withKeystorePassword("Very5ecure")
            .withKeyPassword("ActuallyNotSecure")
            .withKeystore(new File("src/main/resources/io/muserver/resources/localhost.jks"))
            .build();
    }

    @Test(expected = MuException.class)
    public void throwsIfThePasswordIsWrong() {
        SSLContextBuilder.sslContext()
            .withKeystoreType("JKS")
            .withKeystorePassword("Very5ecured")
            .withKeyPassword("ActuallyNotSecure")
            .withKeystore(new File("src/main/resources/io/muserver/resources/localhost.jks"))
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void throwsIfTheFileDoesNotExist() {
        SSLContextBuilder.sslContext()
            .withKeystoreType("JKS")
            .withKeystorePassword("Very5ecure")
            .withKeyPassword("ActuallyNotSecure")
            .withKeystore(new File("src/test/blah"))
            .build();
    }
}