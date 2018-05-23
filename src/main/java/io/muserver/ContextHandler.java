package io.muserver;

import java.net.URI;
import java.util.List;

import static io.muserver.Mutils.urlEncode;

public class ContextHandler implements MuHandler {

    private final String contextPath;
    private final List<MuHandler> muHandlers;
    private final String slashContextSlash;
    private final String slashContext;

    public ContextHandler(String contextPath, List<MuHandler> muHandlers) {
        this.contextPath = urlEncode(Mutils.trim(contextPath, "/"));
        this.muHandlers = muHandlers;
        this.slashContextSlash = "/" + this.contextPath + "/";
        this.slashContext = "/" + this.contextPath;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        String rp = request.relativePath();
        if (rp.equals(slashContext)) {
            URI cur = request.uri();
            URI newUri = new URI(cur.getScheme(), cur.getUserInfo(), cur.getHost(), cur.getPort(), cur.getPath() + "/", cur.getQuery(), cur.getFragment());
            response.redirect(newUri);
            return true;
        }
        if (rp.startsWith(slashContextSlash)) {
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
