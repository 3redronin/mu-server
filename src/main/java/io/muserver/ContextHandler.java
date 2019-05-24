package io.muserver;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ContextHandler implements MuHandler {

    private final boolean hasContext;
    private final String contextPath;
    private final List<MuHandler> muHandlers;
    private final String slashContextSlash;
    private final String slashContext;

    public ContextHandler(String contextPath, List<MuHandler> muHandlers) {
        String slashTrimmed = Mutils.trim(Mutils.coalesce(contextPath, "").trim(), "/");
        this.hasContext = !slashTrimmed.isEmpty();
        this.contextPath = Stream.of(slashTrimmed.split("/"))
            .map(Mutils::urlEncode)
            .collect(Collectors.joining("/"));
        this.muHandlers = muHandlers;
        this.slashContextSlash = "/" + this.contextPath + "/";
        this.slashContext = "/" + this.contextPath;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        String rp = request.relativePath();
        if (hasContext && rp.equals(slashContext)) {
            URI cur = request.uri();
            URI newUri = new URI(cur.getScheme(), cur.getUserInfo(), cur.getHost(), cur.getPort(), cur.getPath() + "/", cur.getQuery(), cur.getFragment());
            response.redirect(newUri);
            return true;
        }
        if (rp.startsWith(slashContextSlash) || !hasContext) {
            String originalContextPath = request.contextPath();
            String originalRelativePath = request.relativePath();
            if (hasContext) {
                ((NettyRequestAdapter) request).addContext(contextPath);
            }
            for (MuHandler muHandler : muHandlers) {
                if (muHandler.handle(request, response)) {
                    return true;
                }
            }
            ((NettyRequestAdapter) request).setPaths(originalContextPath, originalRelativePath);
        }
        return false;
    }
}
