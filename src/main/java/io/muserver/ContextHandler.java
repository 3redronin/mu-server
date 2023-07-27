package io.muserver;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A handler that wraps a list of other handlers and serves them at a certain path prefix (or context).
 * <p>Use this when you want to serve multiple handlers with the same path prefix.</p>
 */
public class ContextHandler implements MuHandler {

    private final boolean hasContext;
    private final String contextPath;
    private final List<MuHandler> muHandlers;
    private final String slashContextSlash;
    private final String slashContext;

    /**
     * Creates a new handler
     * @param contextPath The patch
     * @param muHandlers The handlers
     */
    ContextHandler(String contextPath, List<MuHandler> muHandlers) {
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
            boolean mu1 = request instanceof NettyRequestAdapter;
            if (hasContext) {
                if (mu1) {
                    ((NettyRequestAdapter) request).addContext(contextPath);
                } else {
                    ((MuRequestImpl) request).addContext(contextPath);
                }
            }
            for (MuHandler muHandler : muHandlers) {
                if (muHandler.handle(request, response)) {
                    return true;
                }
            }
            if (mu1) {
                ((NettyRequestAdapter) request).setPaths(originalContextPath, originalRelativePath);
            } else {
                ((MuRequestImpl) request).setPaths(originalContextPath, originalRelativePath);
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "ContextHandler{" +
            "context='" + contextPath + '\'' +
            ", children='" + muHandlers + '\'' +
            '}';
    }
}
