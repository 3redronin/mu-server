package io.muserver;

import java.util.List;

import static io.muserver.Mutils.urlEncode;

public class ContextHandler implements MuHandler {

    private final String contextPath;
    private final List<MuHandler> muHandlers;
    private final String slashContextSlash;

    public ContextHandler(String contextPath, List<MuHandler> muHandlers) {
        this.contextPath = urlEncode(Mutils.trim(contextPath, "/"));
        this.muHandlers = muHandlers;
        this.slashContextSlash = "/" + this.contextPath + "/";
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        if (request.relativePath().startsWith(slashContextSlash)) {
            ((NettyRequestAdapter) request).addContext(contextPath);
            for (MuHandler muHandler : muHandlers) {
                if (muHandler.handle(request, response)) {
                    return true;
                }
            }
        }
        return false;
    }
}
