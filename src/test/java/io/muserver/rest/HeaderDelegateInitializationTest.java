package io.muserver.rest;

import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class HeaderDelegateInitializationTest {

    @Test
    public void mediaTypeDelegateSetsRuntimeWhenUsedDirectly() throws Exception {
        runInFreshJvm("media-type");
    }

    @Test
    public void cookieDelegateSetsRuntimeWhenUsedDirectly() throws Exception {
        runInFreshJvm("cookie");
    }

    @Test
    public void newCookieDelegateSetsRuntimeWhenUsedDirectly() throws Exception {
        runInFreshJvm("new-cookie");
    }

    private static void runInFreshJvm(String delegate) throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(
            java,
            "-cp",
            classPath,
            HeaderDelegateInitializationTest.class.getName(),
            delegate)
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

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new AssertionError("Expected a header-delegate name");
        }

        switch (args[0]) {
            case "media-type":
                MediaType mediaType = MediaTypeHeaderDelegate
                    .fromStrings(Collections.singletonList("text/plain"))
                    .get(0);
                if (!MediaType.TEXT_PLAIN_TYPE.equals(mediaType)) {
                    throw new AssertionError("Media type delegate did not parse text/plain");
                }
                break;
            case "cookie":
                Cookie cookie = new CookieHeaderDelegate().fromString("session=abc");
                if (!"abc".equals(cookie.getValue())) {
                    throw new AssertionError("Cookie delegate did not parse the cookie");
                }
                break;
            case "new-cookie":
                NewCookie newCookie = new NewCookieHeaderDelegate().fromString("session=abc");
                if (!"abc".equals(newCookie.getValue())) {
                    throw new AssertionError("New-cookie delegate did not parse the cookie");
                }
                break;
            default:
                throw new AssertionError("Unknown header delegate " + args[0]);
        }

        if (RuntimeDelegate.getInstance().getClass() != MuRuntimeDelegate.class) {
            throw new AssertionError("Direct header-delegate use did not select MuRuntimeDelegate");
        }
    }
}
