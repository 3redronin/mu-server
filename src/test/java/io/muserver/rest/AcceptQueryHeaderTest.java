package io.muserver.rest;

import jakarta.ws.rs.core.MediaType;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class AcceptQueryHeaderTest {
    @Test public void rangesAreSortedDeduplicatedAndParametersAreStructuredStrings() {
        assertEquals("\"*/*\", \"application/json\";charset=\"UTF-8\", \"text/*\", \"text/plain\";profile=\"a\\\"b\\\\c\"",
            AcceptQueryHeader.format(Arrays.asList(MediaType.WILDCARD_TYPE, new MediaType("text", "*"),
                new MediaType("application", "json", Collections.singletonMap("charset", "UTF-8")),
                new MediaType("text", "plain", Collections.singletonMap("profile", "a\"b\\c")), MediaType.WILDCARD_TYPE)));
    }
    @Test public void unsupportedRangesOrParametersOmitTheEntireAdvertisement() {
        for (MediaType unsupported : Arrays.asList(new MediaType("application", "*+json"), new MediaType("*", "json"),
            new MediaType("text", "plain", Collections.singletonMap("9invalid", "value")),
            new MediaType("text", "plain", Collections.singletonMap("profile", "日本語")))) {
            assertNull(AcceptQueryHeader.format(Arrays.asList(MediaType.TEXT_PLAIN_TYPE, unsupported)));
        }
        assertNull(AcceptQueryHeader.format(Collections.emptyList()));
    }
}
