package validation;

import io.muserver.*;
import io.muserver.rest.RestHandlerBuilder;
import jakarta.ws.rs.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Test application compiled independently against each checkout's public API. */
public final class Fixture {
    private static final AtomicLong handled = new AtomicLong();
    private static final AtomicLong completed = new AtomicLong();
    private static final AtomicLong failed = new AtomicLong();
    private static final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();

    @jakarta.ws.rs.Path("/rest")
    public static class Resource {
        @GET @jakarta.ws.rs.Path("/hello") @Produces("text/plain")
        public String hello(@QueryParam("value") @DefaultValue("hello") String value) { return value; }
        @GET @jakarta.ws.rs.Path("/error")
        public String error() { throw new NotFoundException("fixture missing"); }
        @POST @jakarta.ws.rs.Path("/echo") @Produces("text/plain")
        public String echo(String body) { return body; }
    }

    private static int number(MuRequest req, String name, int fallback, int maximum) {
        String value = req.query().get(name);
        return value == null ? fallback : Math.max(0, Math.min(maximum, Integer.parseInt(value)));
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> settings = new HashMap<>();
        for (String arg : args) { String[] pair = arg.split("=", 2); settings.put(pair[0], pair[1]); }
        MuServerBuilder builder = MuServerBuilder.muServer().withInterface("127.0.0.1")
            .withHttpPort(0).withHttpsPort(0)
            .withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable());
        try { builder.getClass().getMethod("withTempDirectory", Path.class).invoke(builder, Path.of(settings.get("uploads"))); }
        catch (NoSuchMethodException ignored) { /* Older versions use java.io.tmpdir. */ }
        if (!"defaults".equals(settings.get("configuration"))) {
            builder.withMaxHeadersSize(8192).withMaxUrlSize(8192)
                .withIdleTimeout(10, TimeUnit.SECONDS).withRequestTimeout(3, TimeUnit.SECONDS)
                .withMaxRequestSize(24 * 1024 * 1024);
        }
        if (Boolean.parseBoolean(settings.getOrDefault("proxy", "false"))) {
            builder.getClass().getMethod("withHAProxyProtocolEnabled", boolean.class).invoke(builder, true);
        }
        if (settings.containsKey("keystore")) {
            HttpsConfigBuilder https = HttpsConfigBuilder.httpsConfig().withKeystore(new File(settings.get("keystore")))
                .withKeystoreType("PKCS12").withKeystorePassword("validation");
            if (settings.containsKey("client-ca")) {
                java.security.KeyStore trust = java.security.KeyStore.getInstance("PKCS12");
                trust.load(null, null);
                try (InputStream cert = new FileInputStream(settings.get("client-ca"))) {
                    trust.setCertificateEntry("client", java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(cert));
                }
                javax.net.ssl.TrustManagerFactory tm = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                tm.init(trust); https.withClientCertificateTrustManager(tm.getTrustManagers()[0]);
                if (settings.containsKey("client-auth")) {
                    Class<?> auth = Class.forName("io.muserver.ClientCertificateAuthentication");
                    Object value = Arrays.stream(auth.getEnumConstants()).filter(e -> e.toString().equals(settings.get("client-auth"))).findFirst().orElseThrow();
                    https.getClass().getMethod("withClientCertificateAuthentication", auth).invoke(https, value);
                }
            }
            builder.withHttpsConfig(https);
        }
        builder.addResponseCompleteListener(info -> {
            completed.incrementAndGet();
            if (!info.completedSuccessfully()) failed.incrementAndGet();
        });
        WebSocketHandlerBuilder webSockets = WebSocketHandlerBuilder.webSocketHandler();
        if ("independent".equals(settings.get("configuration"))) {
            // Protocol-tool profile: avoid confusing a configured size limit with bad framing.
            webSockets.withMaxFramePayloadLength(16 * 1024 * 1024)
                .withIdleReadTimeout(60, TimeUnit.SECONDS);
            try { webSockets.getClass().getMethod("withMaxMessageLength", long.class)
                .invoke(webSockets, 16L * 1024 * 1024); }
            catch (NoSuchMethodException ignored) { /* Legacy versions expose only a frame limit. */ }
        }
        builder.addHandler(webSockets.withWebSocketFactory((req, headers) ->
            req.relativePath().equals("/ws") ? new EchoSocket() : null));
        builder.addHandler(RestHandlerBuilder.restHandler(new Resource()));
        builder.addHandler((req, resp) -> {
            handled.incrementAndGet();
            resp.contentType("text/plain");
            // Stable header values make cold/warm header compression comparable.
            resp.headers().set("Date", "Sun, 13 Sep 2026 00:00:00 GMT");
            resp.headers().set("X-Fixture", "mu-validation-v1");
            String route = req.relativePath();
            switch (route) {
                case "/": case "/hello": resp.write("hello"); break;
                case "/echo": resp.write(req.readBodyAsString()); break;
                case "/early": {
                    try (InputStream in = req.inputStream().orElseThrow()) { in.read(); }
                    resp.write("accepted"); break;
                }
                case "/digest": case "/slow-upload": {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long size = 0;
                    InputStream in = req.inputStream().orElse(InputStream.nullInputStream());
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, count); size += count;
                        if (route.equals("/slow-upload")) Thread.sleep(number(req, "delay", 2, 100));
                    }
                    StringBuilder hex = new StringBuilder();
                    for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
                    resp.write(size + ":" + hex); break;
                }
                case "/metadata":
                    resp.write(req.method() + "\n" + req.relativePath() + "\n" + req.query().get("value")); break;
                case "/client-cert": resp.write(Boolean.toString(req.connection().clientCertificate().isPresent())); break;
                case "/proxy": {
                    Object info = HttpConnection.class.getMethod("proxyInfo").invoke(req.connection());
                    Optional<?> optional = (Optional<?>) info;
                    if (optional.isEmpty()) resp.write("none");
                    else {
                        Class<?> api = Class.forName("io.muserver.ProxiedConnectionInfo");
                        Object pi = optional.get();
                        resp.write(api.getMethod("sourceAddress").invoke(pi) + ":" + api.getMethod("sourcePort").invoke(pi)
                            + "->" + api.getMethod("destinationAddress").invoke(pi) + ":" + api.getMethod("destinationPort").invoke(pi));
                    }
                    break;
                }
                case "/stream": case "/sse": {
                    int count = number(req, "count", 4, 10000);
                    int delay = number(req, "delay", 0, 1000);
                    if (route.equals("/sse")) resp.contentType("text/event-stream");
                    for (int i = 0; i < count; i++) {
                        resp.sendChunk(route.equals("/sse") ? "data: " + i + "\n\n" : "chunk-" + i + "\n");
                        if (delay > 0) Thread.sleep(delay);
                    }
                    break;
                }
                case "/bytes": {
                    int size = number(req, "size", 4096, 16 * 1024 * 1024);
                    int piece = Math.max(1, number(req, "piece", 8192, 65536));
                    boolean fixed = "true".equals(req.query().get("fixed"));
                    if (fixed) resp.headers().set("Content-Length", size);
                    byte[] bytes = new byte[Math.min(piece, Math.max(1, size))];
                    Arrays.fill(bytes, (byte) 'a');
                    OutputStream out = resp.outputStream();
                    for (int offset = 0; offset < size; offset += bytes.length) {
                        out.write(bytes, 0, Math.min(bytes.length, size - offset));
                        if ("true".equals(req.query().get("flush"))) out.flush();
                    }
                    break;
                }
                case "/gate": {
                    String id = Objects.requireNonNull(req.query().get("id"));
                    if (!gates.computeIfAbsent(id, ignored -> new CountDownLatch(1)).await(30, TimeUnit.SECONDS)) {
                        resp.status(504); resp.write("gate timeout");
                    } else resp.write("released");
                    gates.remove(id); break;
                }
                case "/upload": resp.write("files=" + req.uploadedFiles("file").size()); break;
                case "/trailers": {
                    req.readBodyAsString();
                    Object trailers = MuRequest.class.getMethod("trailers").invoke(req);
                    resp.write(((Headers) trailers).get("x-checksum")); break;
                }
                default: resp.status(404); resp.write("not found");
            }
            return true;
        });
        MuServer server = builder.start();
        System.out.println("READY " + server.httpUri().getPort() + " " + server.httpsUri().getPort());
        System.out.flush();
        try (BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String command;
            while ((command = commands.readLine()) != null) {
                String[] parts = command.split(" ", 2);
                if (parts[0].equals("STATS")) {
                    long direct = ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)
                        .stream().mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
                    System.out.println("STATS " + handled + " " + completed + " " + failed + " "
                        + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() + " "
                        + ManagementFactory.getThreadMXBean().getThreadCount() + " " + direct);
                } else if (parts[0].equals("RELEASE")) {
                    gates.computeIfAbsent(parts[1], ignored -> new CountDownLatch(1)).countDown();
                    System.out.println("RELEASED");
                } else if (parts[0].equals("RELEASE_AFTER")) {
                    String[] release = parts[1].split(" ");
                    Thread thread = new Thread(() -> {
                        try {
                            Thread.sleep(Long.parseLong(release[1]));
                            gates.computeIfAbsent(release[0], ignored -> new CountDownLatch(1)).countDown();
                        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }, "fixture-release");
                    thread.setDaemon(true); thread.start();
                    System.out.println("SCHEDULED");
                } else if (parts[0].equals("STOP")) {
                    boolean clean = server.stop(Long.parseLong(parts[1]), TimeUnit.MILLISECONDS);
                    System.out.println("STOPPED " + clean); System.out.flush(); return;
                } else if (parts[0].equals("RELOAD")) {
                    server.changeHttpsConfig(HttpsConfigBuilder.httpsConfig().withKeystore(new File(parts[1]))
                        .withKeystoreType("PKCS12").withKeystorePassword("validation"));
                    System.out.println("RELOADED");
                } else throw new IllegalArgumentException("Unknown control command");
                System.out.flush();
            }
        } finally { server.stop(); }
    }
}
