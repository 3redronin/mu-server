package io.muserver.handlers;

import io.muserver.HeaderNames;
import io.muserver.Headers;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ResourceTypeTest {

    @Test
    public void canDiscoverResourceTypes() {
        Map<String, ResourceType> map = ResourceType.DEFAULT_EXTENSION_MAPPINGS;
        assertThat(map.get("jpg"), is(ResourceType.IMAGE_JPEG));
        assertThat(map.get("jpeg"), is(ResourceType.IMAGE_JPEG));
    }

    @Test
    public void gzippablesAreFound() {
        Set<String> set = ResourceType.gzippableMimeTypes(ResourceType.getResourceTypes());
        assertThat(set.contains("image/jpeg"), is(false));
        assertThat(set.contains("text/html"), is(true));
    }

    @Test
    public void allMimeTypesAreValid() {
        Headers headers = Headers.http1Headers();
        for (ResourceType rt : ResourceType.DEFAULT_EXTENSION_MAPPINGS.values()) {
            headers.set(HeaderNames.CONTENT_TYPE, rt.mimeType());
        }
    }

}