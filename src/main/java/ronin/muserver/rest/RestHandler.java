package ronin.muserver.rest;

import ronin.muserver.MuHandler;
import ronin.muserver.MuRequest;
import ronin.muserver.MuResponse;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class RestHandler implements MuHandler {

    private final Set<ResourceClass> resources;

    public RestHandler(Object... restResources) {
        HashSet<ResourceClass> set = new HashSet<>();
        for (Object restResource : restResources) {
            set.add(ResourceClass.fromObject(restResource));
        }

        this.resources = Collections.unmodifiableSet(set);
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {

        return false;
    }
}
