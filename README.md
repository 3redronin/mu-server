# mu-acme

A simple way to get free HTTPS certs for your [Mu Server](https://muserver.io/) from 
[Let's Encrypt](https://letsencrypt.org/) or other ACME providers with automated renewal.

[![Javadocs](https://www.javadoc.io/badge/io.muserver/mu-acme.svg)](https://www.javadoc.io/doc/io.muserver/mu-acme)


## Quick start

In order to have an SSL certificate granted to you, you need to prove that you
own the domain name. The mu-acme library will talk to the ACME server on your behalf
to prove ownership. In order for this to work, you need to have a domain name that
is pointing to your server.  

Once DNS config is complete, the rest can be handled in code. Add dependencies on Mu Server and Mu ACME:

````xml
<dependency>
    <groupId>io.muserver</groupId>
    <artifactId>mu-server</artifactId>
    <version>RELEASE</version>
</dependency>
<dependency>
    <groupId>io.muserver</groupId>
    <artifactId>mu-acme</artifactId>
    <version>RELEASE</version>
</dependency>
````

Start a server:

````java
import io.muserver.*;
import io.muserver.acme.*;

public class QuickStart {
    
    public static void main(String[] args) throws Exception {
        
        AcmeCertManager certManager = AcmeCertManagerBuilder.letsEncryptStaging()
            .withDomain("your-domain.example.org")
            .withConfigDir("target/ssl-config")
            .build();

        MuServer server = MuServerBuilder.muServer()
            .withHttpPort(80)
            .withHttpsPort(443)
            .withHttpsConfig(certManager.createSSLContext())
            .addHandler(certManager.createHandler())
            .addHandler(Method.GET, "/", (req, resp, path) -> {
                resp.write("Hello, world");
            })
            .start();

        certManager.start(server);
        System.out.println("Started server at " + server.uri());
        
    }

}
````

Assuming that `your-domain.example.org` resolves to the server where this code is
deployed, you will now have free, automatically renewed SSL certificates.

### More details

For more information on how this works, and other configuration options, or how to handle this
when running locally, and how to package into an uber jar, please see 
[the Mu Server ACME integration documentation](https://muserver.io/letsencrypt).

### Acknowledgements

This library is built using the excellent [ACME Java Client](https://github.com/shred/acme4j)
along with [Bouncy Castle](https://www.bouncycastle.org/java.html).
