package io.muserver.rest;

import io.muserver.*;

import java.util.Set;

class OpenApiDocumentor implements MuHandler {
    private final Set<ResourceClass> roots;
    private final EntityProviders entityProviders;
    private final String openApiJsonUrl;

    OpenApiDocumentor(Set<ResourceClass> roots, EntityProviders entityProviders, String openApiJsonUrl) {
        this.roots = roots;
        this.entityProviders = entityProviders;
        this.openApiJsonUrl = Mutils.trim(openApiJsonUrl, "/");
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        String relativePath = Mutils.trim(request.relativePath(), "/");
        if (request.method() != Method.GET || !relativePath.equals(openApiJsonUrl)) {
            return false;
        }

        return true;
    }
}
