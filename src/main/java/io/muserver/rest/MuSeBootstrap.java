package io.muserver.rest;

import io.muserver.ContextHandlerBuilder;
import io.muserver.HttpsConfigBuilder;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;

import javax.net.ssl.SSLContext;
import java.lang.reflect.InvocationTargetException;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

final class MuSeBootstrap {
    private static final Map<String, Class<?>> PROPERTY_TYPES = propertyTypes();

    private MuSeBootstrap() {
    }

    static CompletionStage<SeBootstrap.Instance> start(Application application,
                                                       SeBootstrap.Configuration requested) {
        try {
            Objects.requireNonNull(application, "application");
            Objects.requireNonNull(requested, "configuration");

            String protocol = requested.protocol();
            String host = requested.host();
            int requestedPort = requested.port();
            String rootPath = requested.rootPath();
            if (requestedPort < SeBootstrap.Configuration.DEFAULT_PORT || requestedPort > 65535) {
                throw new IllegalArgumentException("Invalid port: " + requestedPort);
            }

            int port = requestedPort == SeBootstrap.Configuration.DEFAULT_PORT
                ? SeBootstrap.Configuration.FREE_PORT
                : requestedPort;
            MuServerBuilder serverBuilder = MuServerBuilder.muServer().withInterface(host);
            if ("HTTP".equalsIgnoreCase(protocol)) {
                serverBuilder.withHttpPort(port);
            } else if ("HTTPS".equalsIgnoreCase(protocol)) {
                if (requested.sslClientAuthentication()
                    != SeBootstrap.Configuration.SSLClientAuthentication.NONE) {
                    throw new UnsupportedOperationException("SEBootstrap TLS client authentication is not supported");
                }
                serverBuilder.withHttpsPort(port)
                    .withHttpsConfig(new HttpsConfigBuilder().withSSLContext(requested.sslContext()));
            } else {
                throw new IllegalArgumentException("Unsupported protocol: " + protocol);
            }

            String applicationPath = applicationPath(application.getClass());
            String mountPath = joinPaths(rootPath, applicationPath);
            serverBuilder.addHandler(ContextHandlerBuilder.context(mountPath)
                .addHandler(ApplicationRegistrar.from(application, true)));
            MuServer server = serverBuilder.start();
            SeBootstrap.Configuration actual = new ConfigurationBuilder()
                .protocol(protocol)
                .host(host)
                .port(server.uri().getPort())
                .rootPath(rootPath)
                .sslContext(requested.sslContext())
                .sslClientAuthentication(requested.sslClientAuthentication())
                .build();
            return CompletableFuture.completedFuture(new Instance(server, actual));
        } catch (Throwable failure) {
            CompletableFuture<SeBootstrap.Instance> failed = new CompletableFuture<>();
            failed.completeExceptionally(failure);
            return failed;
        }
    }

    static CompletionStage<SeBootstrap.Instance> start(Class<? extends Application> applicationClass,
                                                       SeBootstrap.Configuration configuration) {
        try {
            Objects.requireNonNull(applicationClass, "applicationClass");
            return start(applicationClass.getDeclaredConstructor().newInstance(), configuration);
        } catch (InvocationTargetException e) {
            CompletableFuture<SeBootstrap.Instance> failed = new CompletableFuture<>();
            failed.completeExceptionally(e.getCause());
            return failed;
        } catch (Throwable failure) {
            CompletableFuture<SeBootstrap.Instance> failed = new CompletableFuture<>();
            failed.completeExceptionally(failure);
            return failed;
        }
    }

    private static String applicationPath(Class<?> applicationClass) {
        ApplicationPath annotation = applicationClass.getAnnotation(ApplicationPath.class);
        return annotation == null ? "" : annotation.value();
    }

    private static String joinPaths(String first, String second) {
        String left = Objects.requireNonNull(first, "rootPath").trim();
        String right = Objects.requireNonNull(second, "applicationPath").trim();
        if (left.isEmpty() || "/".equals(left)) {
            return right;
        }
        if (right.isEmpty() || "/".equals(right)) {
            return left;
        }
        return left.replaceAll("/+$", "") + "/" + right.replaceAll("^/+", "");
    }

    private static Map<String, Class<?>> propertyTypes() {
        Map<String, Class<?>> types = new LinkedHashMap<>();
        types.put(SeBootstrap.Configuration.PROTOCOL, String.class);
        types.put(SeBootstrap.Configuration.HOST, String.class);
        types.put(SeBootstrap.Configuration.PORT, Integer.class);
        types.put(SeBootstrap.Configuration.ROOT_PATH, String.class);
        types.put(SeBootstrap.Configuration.SSL_CONTEXT, SSLContext.class);
        types.put(SeBootstrap.Configuration.SSL_CLIENT_AUTHENTICATION,
            SeBootstrap.Configuration.SSLClientAuthentication.class);
        return types;
    }

    static final class ConfigurationBuilder implements SeBootstrap.Configuration.Builder {
        private final Map<String, Object> properties = defaults();

        @Override
        public SeBootstrap.Configuration build() {
            return new Configuration(properties);
        }

        @Override
        public SeBootstrap.Configuration.Builder property(String name, Object value) {
            Objects.requireNonNull(name, "name");
            if (PROPERTY_TYPES.containsKey(name)) {
                if (value == null) {
                    properties.put(name, defaults().get(name));
                } else {
                    properties.put(name, value);
                }
            }
            return this;
        }

        @Override
        public <T> SeBootstrap.Configuration.Builder from(
            BiFunction<String, Class<T>, Optional<T>> propertiesProvider) {
            Objects.requireNonNull(propertiesProvider, "propertiesProvider");
            for (Map.Entry<String, Class<?>> entry : PROPERTY_TYPES.entrySet()) {
                load(propertiesProvider, entry.getKey(), entry.getValue());
            }
            return this;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private <T> void load(BiFunction<String, Class<T>, Optional<T>> provider,
                              String name, Class<?> type) {
            Optional<?> value = Objects.requireNonNull(provider.apply(name, (Class) type),
                "propertiesProvider result");
            value.ifPresent(it -> properties.put(name, it));
        }

        private static Map<String, Object> defaults() {
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put(SeBootstrap.Configuration.PROTOCOL, "HTTP");
            defaults.put(SeBootstrap.Configuration.HOST, "localhost");
            defaults.put(SeBootstrap.Configuration.PORT, SeBootstrap.Configuration.DEFAULT_PORT);
            defaults.put(SeBootstrap.Configuration.ROOT_PATH, "/");
            try {
                defaults.put(SeBootstrap.Configuration.SSL_CONTEXT, SSLContext.getDefault());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("No default SSLContext is available", e);
            }
            defaults.put(SeBootstrap.Configuration.SSL_CLIENT_AUTHENTICATION,
                SeBootstrap.Configuration.SSLClientAuthentication.NONE);
            return defaults;
        }
    }

    private static final class Configuration implements SeBootstrap.Configuration {
        private final Map<String, Object> properties;

        private Configuration(Map<String, Object> properties) {
            this.properties = new LinkedHashMap<>(properties);
        }

        @Override
        public Object property(String name) {
            return properties.get(name);
        }
    }

    private static final class Instance implements SeBootstrap.Instance {
        private final MuServer server;
        private final SeBootstrap.Configuration configuration;
        private final AtomicBoolean stopped = new AtomicBoolean();

        private Instance(MuServer server, SeBootstrap.Configuration configuration) {
            this.server = server;
            this.configuration = configuration;
        }

        @Override
        public SeBootstrap.Configuration configuration() {
            return configuration;
        }

        @Override
        public CompletionStage<StopResult> stop() {
            if (stopped.compareAndSet(false, true)) {
                server.stop();
            }
            return CompletableFuture.completedFuture(new StopResult() {
                @Override
                public <T> T unwrap(Class<T> nativeClass) {
                    return null;
                }
            });
        }

        @Override
        public <T> T unwrap(Class<T> nativeClass) {
            return nativeClass.cast(server);
        }
    }
}
