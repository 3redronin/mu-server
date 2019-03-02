package io.muserver.acme;

import io.muserver.MuHandler;
import io.muserver.MuServer;
import io.muserver.Mutils;
import io.muserver.SSLContextBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeRetryAfterException;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.*;
import java.net.URI;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * <p>This class managers the generation and automatic renewal of HTTPS certificates by using an ACME certificate
 * authority such as <a href="https://letsencrypt.org/">Let's Encrypt</a>.</p>
 * <p>To create a cert manager, please use {@link AcmeCertManagerBuilder#acmeCertManager()} or a convenience
 * method such as {@link AcmeCertManagerBuilder#letsEncrypt()}.</p>
 */
public class AcmeCertManager {
    private static final Logger log = LoggerFactory.getLogger(AcmeCertManager.class);
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    private final List<String> domains;
    private final File certFile;
    private final URI acmeServerURI;
    private final File accountKeyFile;
    private final File domainKeyFile;
    private Login login;
    private volatile String currentToken;
    private volatile String currentContent;
    private final String organization;
    private final String organizationalUnit;
    private final String country;
    private final String state;

    AcmeCertManager(File configDir, URI acmeServerURI, String organization, String organizationalUnit, String country, String state, List<String> domains) {
        this.acmeServerURI = acmeServerURI;
        this.organization = organization;
        this.organizationalUnit = organizationalUnit;
        this.country = country;
        this.state = state;
        this.domains = domains;
        certFile = new File(configDir, "cert-chain.crt");
        accountKeyFile = new File(configDir, "acme-account-key.pem");
        domainKeyFile = new File(configDir, "domain-key.pem");
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * <p>Starts a background thread which creates or renews the certificate when needed. This is set to run
     * immediately and then once every 24 hours. Certs will be renewed when they are due to expire in less
     * than 3 days.</p>
     * @param muServer A started Mu Server instance, which means this method should be called after starting your server.
     */
    public void start(MuServer muServer) {
        if (muServer.httpUri() == null) {
            log.warn("Automated SSL will not work as there is no HTTP URL available on port 80.");
        }
        executorService.scheduleAtFixedRate(() -> {
                try {
                    acquireCertIfNeeded(muServer);
                } catch (Exception e) {
                    log.warn("Error while checking HTTPS cert renewal status", e);
                }
            }, 0, 24, TimeUnit.HOURS
        );
    }

    /**
     * Stops the periodic cert renewal checks.
     */
    public void stop() {
        executorService.shutdown();
        try {
            executorService.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * Forces a check of the cert validity, and creates or renews if needed.
     * @param muServer A started Mu Server instance, which means this method should be called after starting your server.
     * @throws Exception The certificate check or request failed.
     */
    synchronized void acquireCertIfNeeded(MuServer muServer) throws Exception {
        boolean shouldAcquire = false;
        if (!certFile.isFile()) {
            log.info("A new SSL cert is will be created as none exists.");
            shouldAcquire = true;
        } else {
            X509Certificate x509Certificate = PemSslContextFactory.loadX509Certificate(certFile);
            Date expiryDate = x509Certificate.getNotAfter();

            Date soon = new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3));
            if (expiryDate.before(soon)) {
                log.info("The SSL cert will be renewed as it expires at " + expiryDate);
                shouldAcquire = true;
            } else {
                log.info("No SSL cert renewal needed as the cert does not expire until " + expiryDate);
            }
        }
        if (shouldAcquire) {
            acquireCert();
            muServer.changeSSLContext(createSSLContext());
            log.info("Mu Server HTTPS cert updated");
        }
    }

    private synchronized void acquireCert() throws Exception {
        log.info("Logging in to " + acmeServerURI);
        KeyPair keyPair = loadOrCreateKeypair(accountKeyFile);
        Session session = new Session(acmeServerURI);
        Account account = new AccountBuilder()
            .agreeToTermsOfService()
            .useKeyPair(keyPair)
            .create(session);
        login = session.login(account.getLocation(), keyPair);
        log.info("Logged in with " + login.getAccount().getLocation());
        Order order = orderCert();
        waitUntilValid("Waiting for certificate order", order::getStatus, () -> { order.update(); return null; });
        Certificate cert = order.getCertificate();
        try (FileWriter fw = new FileWriter(certFile)) {
            cert.writeCertificate(fw);
        }
        log.info("Cert written to " + Mutils.fullPath(certFile) + " and it expires on " + cert.getCertificate().getNotAfter());
    }

    /**
     * <p>Creates an SSL context from the certificate previously generated by this manager. Use this
     * to configure your server on startup. For example:</p>
     * <pre><code>
     *  server = muServer()
     *      .withHttpPort(80)
     *      .withHttpsPort(443)
     *      .withHttpsConfig(acmeCertManager.createSSLContext())
     *      // add handlers
     *      .start();
     * </code></pre>
     *
     * <p>The first time a server is started, there will be no certificate, in which case</p>
     *
     * @return An SSL Context
     * @throws Exception Thrown if the certificate cannot be loaded.
     */
    public synchronized SSLContext createSSLContext() throws Exception {
        if (!certFile.isFile()) {
            log.info("No cert available yet. Using self-signed cert.");
            return SSLContextBuilder.unsignedLocalhostCert();
        }
        log.info("Using " + Mutils.fullPath(certFile));
        return PemSslContextFactory.getSSLContextFromLetsEncrypt(certFile, domainKeyFile);
    }

    /**
     * Creates a handler that will handle the requests needed from the ACME Certificate Authority
     * to prove that you own the domain name specified.
     * @return A handler that you should add as the first handler to your Mu Server.
     */
    public MuHandler createHandler() {
        return (request, response) -> {
            String token = currentToken;
            String content = currentContent;
            if (token == null || content == null || !request.uri().getPath().equals("/.well-known/acme-challenge/" + token)) {
                return false;
            }
            response.contentType("text/plain");
            response.write(content);
            return true;
        };
    }

    private Order orderCert() throws Exception {
        Order order = login.getAccount().newOrder()
            .domains(domains)
            .create();
        for (Authorization authorization : order.getAuthorizations()) {
            if (authorization.getStatus() != Status.VALID) {
                processAuth(authorization);
            }
        }
        KeyPair domainKeyPair = loadOrCreateKeypair(domainKeyFile);

        CSRBuilder csrb = new CSRBuilder();
        csrb.addDomains(domains);
        if (country != null) csrb.setCountry(country);
        if (organizationalUnit != null) csrb.setOrganizationalUnit(organizationalUnit);
        if (state != null) csrb.setState(state);
        if (organization != null) csrb.setOrganization(organization);
        csrb.sign(domainKeyPair);
        byte[] csr = csrb.getEncoded();
        order.execute(csr);
        return order;
    }

    private void processAuth(Authorization auth) throws Exception {
        Http01Challenge challenge = auth.findChallenge(Http01Challenge.TYPE);
        if (challenge == null) {
            throw new RuntimeException("Could not create cert as no HTTP01 auth was available");
        }
        currentToken = challenge.getToken();
        currentContent = challenge.getAuthorization();
        challenge.trigger();

        waitUntilValid("Waiting for authorization challenge to complete", auth::getStatus, () -> { auth.update(); return null; });
        currentContent = null;
        currentToken = null;
    }

    private static void waitUntilValid(String description, Callable<Status> getStatus, Callable<Void> update) throws Exception {
        Status curStatus;
        int maxAttempts = 100;
        while ( (curStatus = getStatus.call()) != Status.VALID) {
            if (curStatus == Status.INVALID) {
                throw new CertificateOrderException(description + " but status is INVALID. Aborting attempt.");
            }
            log.info(description + ". Current status is " + curStatus);
            Thread.sleep(3000L);
            try {
                update.call();
            } catch (AcmeRetryAfterException e) {
                long waitTime = e.getRetryAfter().getEpochSecond() - System.currentTimeMillis();
                waitTime = Math.max(500, waitTime);
                waitTime = Math.min(10 * 60000, waitTime);
                Thread.sleep(waitTime);
            }
            maxAttempts--;
            if (maxAttempts == 0) {
                throw new CertificateOrderException(description + " but timed out.");
            }
        }
    }

    static KeyPair loadOrCreateKeypair(File file) throws IOException {
        File dir = file.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new RuntimeException("Could not create " + Mutils.fullPath(dir));
        }
        KeyPair keyPair;
        if (file.isFile()) {
            log.info("Using key " + Mutils.fullPath(file));
            try (FileReader fr = new FileReader(file)) {
                keyPair = KeyPairUtils.readKeyPair(fr);
            }
        } else {
            log.info("Creating " + Mutils.fullPath(file));
            keyPair = KeyPairUtils.createKeyPair(2048);
            try (FileWriter fw = new FileWriter(file)) {
                KeyPairUtils.writeKeyPair(keyPair, fw);
            }
        }
        return keyPair;
    }

    /**
     * @return Returns the current version of MuServer, or 0.x if unknown
     */
    public static String artifactVersion() {
        try {
            Properties props = new Properties();
            InputStream in = AcmeCertManager.class.getResourceAsStream("/META-INF/maven/io.muserver/mu-acme/pom.properties");
            if (in == null) {
                return "1.x";
            }
            try {
                props.load(in);
            } finally {
                in.close();
            }
            return props.getProperty("version");
        } catch (Exception ex) {
            return "1.x";
        }
    }

}
