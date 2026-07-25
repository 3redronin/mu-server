package io.muserver.rest;

import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class RuntimeDelegateSystemPropertyTest {

    @Test
    public void systemPropertySelectsMuForSeBootstrap() throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(
            java,
            "-D" + RuntimeDelegate.JAXRS_RUNTIME_DELEGATE_PROPERTY + "=" + MuRuntimeDelegate.class.getName(),
            "-cp",
            classPath,
            RuntimeDelegateSystemPropertyTest.class.getName(),
            "verify-system-property")
            .redirectErrorStream(true)
            .start();

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat("Child JVM output:\n" + output, finished, is(true));
        assertThat("Child JVM output:\n" + output, process.exitValue(), is(0));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !"verify-system-property".equals(args[0])) {
            throw new AssertionError("Expected the child-process marker argument");
        }

        SeBootstrap.Configuration configuration = SeBootstrap.Configuration.builder()
            .port(SeBootstrap.Configuration.FREE_PORT)
            .build();
        RuntimeDelegate selected = RuntimeDelegate.getInstance();

        if (selected.getClass() != MuRuntimeDelegate.class) {
            throw new AssertionError("Expected MuRuntimeDelegate but got " + selected.getClass().getName());
        }
        if (MuRuntimeDelegate.ensureSet() != selected) {
            throw new AssertionError("ensureSet() did not retain the delegate selected by the system property");
        }
        if (!"HTTP".equals(configuration.protocol())) {
            throw new AssertionError("Expected Mu's default HTTP configuration");
        }

        SeBootstrap.Instance instance = SeBootstrap.start(new Application() {
        }, configuration).toCompletableFuture().get(10, TimeUnit.SECONDS);
        try {
            if (instance.configuration().port() <= 0) {
                throw new AssertionError("Expected SeBootstrap to bind to a free port");
            }
        } finally {
            instance.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
