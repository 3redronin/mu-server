package ronin.muserver.handlers;

import org.junit.Test;

import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class ResourceTypeTest {

    @Test
    public void canDiscoverResourceTypes() {
        Map<String, ResourceType> map = ResourceType.DEFAULT_EXTENSION_MAPPINGS;
        assertThat(map.get("jpg"), is(ResourceType.IMAGE_JPEG));
        assertThat(map.get("jpeg"), is(ResourceType.IMAGE_JPEG));
    }

}