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

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


class AcmeCertManagerImpl implements AcmeCertManager {
    private static final Logger log = LoggerFactory.getLogger(AcmeCertManagerImpl.class);
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
    private MuServer muServer;

    AcmeCertManagerImpl(File configDir, URI acmeServerURI, String organization, String organizationalUnit, String country, String state, List<String> domains) {
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


    @Override
    public void start(MuServer muServer) {
        Mutils.notNull("muServer", muServer);
        if (muServer.httpUri() == null) {
            log.warn("Automated SSL will not work as there is no HTTP URL available on port 80.");
        }
        this.muServer = muServer;
        executorService.scheduleAtFixedRate(() -> {
                try {
                    acquireCertIfNeeded();
                } catch (CertificateOrderException coe) {
                    log.warn(coe.getMessage());
                } catch (Exception e) {
                    log.warn("Error while checking HTTPS cert renewal status", e);
                }
            }, 0, 24, TimeUnit.HOURS
        );
    }


    @Override
    public void stop() {
        executorService.shutdown();
        try {
            executorService.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public void forceRenew() throws Exception {
        if (muServer == null) {
            throw new IllegalStateException("Renewal can only occur after the start(MuServer) method is called");
        }
        acquireCert();
        muServer.changeSSLContext(createSSLContext());
        log.info("Mu Server HTTPS cert updated");
    }


    synchronized void acquireCertIfNeeded() throws Exception {
        boolean shouldAcquire = false;
        if (!certFile.isFile()) {
            log.info("A new SSL cert will be created as none exists.");
            shouldAcquire = true;
        } else {
            Collection<X509Certificate> x509Certificates = PemSslContextFactory.loadX509Certificate(certFile);

            for (X509Certificate x509Certificate : x509Certificates) {

                Date expiryDate = x509Certificate.getNotAfter();

                Date soon = new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7));
                if (expiryDate.before(soon)) {
                    log.info("The SSL cert will be renewed as it expires at " + expiryDate);
                    shouldAcquire = true;
                } else {
                    log.info("No SSL cert renewal needed as the cert does not expire until " + expiryDate);
                }
            }
        }
        if (shouldAcquire) {
            forceRenew();
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
        waitUntilValid("Waiting for certificate order", order::getStatus, () -> {
            order.update();
            return null;
        });
        Certificate cert = order.getCertificate();
        try (FileWriter fw = new FileWriter(certFile)) {
            cert.writeCertificate(fw);
        }
        log.info("Cert written to " + Mutils.fullPath(certFile) + " and it expires on " + cert.getCertificate().getNotAfter());
    }


    @Override
    public synchronized SSLContextBuilder createSSLContext() throws Exception {
        if (!certFile.isFile()) {
            log.info("No cert available yet. Using self-signed cert.");
            return SSLContextBuilder.unsignedLocalhostCertBuilder();
        }
        log.info("Using " + Mutils.fullPath(certFile));
        return SSLContextBuilder.sslContext()
            .withProtocols("TLSv1.2")
            .withKeyManagerFactory(
                PemSslContextFactory.getKeyManagerFactory(certFile, domainKeyFile)
            );
    }


    @Override
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

        waitUntilValid("Waiting for authorization challenge to complete", auth::getStatus, () -> {
            auth.update();
            return null;
        });
        currentContent = null;
        currentToken = null;
    }

    private static void waitUntilValid(String description, Callable<Status> getStatus, Callable<Void> update) throws Exception {
        Status curStatus;
        int maxAttempts = 100;
        while ((curStatus = getStatus.call()) != Status.VALID) {
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


}
