package io.muserver.handlers;

import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

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
}