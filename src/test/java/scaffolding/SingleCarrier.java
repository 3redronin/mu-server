package scaffolding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs a bounded child JVM so a carrier-pinning regression cannot hang the test suite. */
public final class SingleCarrier {
    private SingleCarrier() { }

    public static void run(Class<?> mainClass, Path log, String... arguments) throws Exception {
        run(mainClass, log, 1, 20, arguments);
    }

    public static void run(Class<?> mainClass, Path log, int carriers, int timeoutSeconds, String... arguments) throws Exception {
        assumeTrue(Runtime.version().feature() >= 21, "Virtual threads require Java 21");
        runOnAnyJdk(mainClass, log, carriers, timeoutSeconds, arguments);
    }

    public static void runOnAnyJdk(Class<?> mainClass, Path log, int carriers, int timeoutSeconds, String... arguments) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var command = new ArrayList<>(List.of(java,
            "-Djdk.virtualThreadScheduler.parallelism=" + carriers, "-Djdk.virtualThreadScheduler.maxPoolSize=" + carriers,
            "-cp", classPath, mainClass.getName()));
        command.addAll(List.of(arguments));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished;
        try {
            finished = child.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Child JVM did not terminate");
            }
        }
        String output = Files.readString(log);
        assertTrue(finished, "Child JVM stalled:\n" + output);
        assertEquals(0, child.exitValue(), "Child JVM failed:\n" + output);
    }

    /** Reflection keeps the test sources compatible with Java 11. */
    public static ExecutorService executor() throws Exception {
        return (ExecutorService) Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
    }
}
