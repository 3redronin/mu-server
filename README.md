# mu-acme

A simple way to get free HTTPS certs for your [Mu Server](https://muserver.io/) from 
[Let's Encrypt](https://letsencrypt.org/) or other ACME providers.

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

The AcmeCertManager needs to know the address of an ACME server. Convenience methods for the
Let's Encrypt staging and production services are predefined, but any ACME service can be
used:

````java
AcmeCertManagerBuilder.acmeCertManager()
    .withAcmeServerURI(URI.create("ACME server URI"));
````

The config dir is where mu-acme will write various files. It is recommended that you back up
this directory and keep it secure as it contains your ACME user key, domain private key, and
the actual server certificate.

With the cert manager built, you can start a server. Note that most ACME providers require you
to have an HTTP port open on port 80. You can directly open port 80 like in the example above
or use something like IP Tables to redirect port 80 to another port.

The cert manager will provide the SSL context. The first time you start the server, there is
no cert available, and a self-signed cert is temporarily used until one is acquired (which is
typically within a few seconds). On subsequent server restarts, the cert from the `configDir`
is used.

The first handler you add to your server should be the handler from the cert manager. This is
used by the library to prove that you own the domain and will not have any other effect.

Finally, after starting the server, you need to start the cert manager. This will start a periodic
check of the cert validity. If the cert is due to expire within 3 days, then a new cert is acquired
and Mu Server will start using the new cert. No restart or manual intervention required.

## HSTS and redirects

Mu Server provides a handler which can redirect all traffic to HTTPS and set HSTS headers.
Just add the following handler AFTER the acme handler when building the server:

````java
.addHandler(certManager.createHandler())
.addHandler(
    HttpsRedirectorBuilder.toHttpsPort(443)
        .withHSTSExpireTime(365, TimeUnit.DAYS)
        .includeSubDomains(true)
)
````

## Running locally

The `AcmeCertManagerBuilder` has a `disable(boolean)` method. If you pass true to this method
when building the manager, then a no-op manager will be returned. You can then use the cert
manager as if it was a real one, however no certs will be requested and a self-signed cert
will be used.

## Packaging into an uber jar

If packaged as an uber jar, you will need to exclude some files.
The following is an example using the `maven-shade-plugin`:

````xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>2.4.3</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>shade</goal>
            </goals>
            <configuration>
                <transformers>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                        <mainClass>org.example.yourapp.App</mainClass>
                    </transformer>
                </transformers>
                <filters>
                    <filter>
                        <artifact>*:*</artifact>
                        <excludes>
                            <exclude>META-INF/*.SF</exclude>
                            <exclude>META-INF/*.DSA</exclude>
                            <exclude>META-INF/*.RSA</exclude>
                        </excludes>
                    </filter>
                </filters>
            </configuration>
        </execution>
    </executions>
</plugin>
````

### Acknowledgements

This library is built using the excellent [ACME Java Client](https://github.com/shred/acme4j)
along with [Bouncy Castle](https://www.bouncycastle.org/java.html).
