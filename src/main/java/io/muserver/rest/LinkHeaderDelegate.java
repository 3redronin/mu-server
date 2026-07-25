package io.muserver.rest;

import io.muserver.Mutils;
import io.muserver.ParameterizedHeaderWithValue;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriBuilderException;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.*;

import static io.muserver.Mutils.coalesce;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

class LinkHeaderDelegate implements RuntimeDelegate.HeaderDelegate<Link> {
    private static final RuntimeDelegate.HeaderDelegate<Link> HEADER_DELEGATE;
    static {
        MuRuntimeDelegate.ensureSet();
        HEADER_DELEGATE =
            RuntimeDelegate.getInstance().createHeaderDelegate(Link.class);
    }

    @Override
    public Link fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value cannot be null");
        }
        List<ParameterizedHeaderWithValue> hList = ParameterizedHeaderWithValue.fromString(value);
        if (hList.isEmpty()) {
            throw new IllegalArgumentException("Could not parse link value");
        }
        ParameterizedHeaderWithValue h = hList.get(0);

        String uri = h.value();
        if (!uri.startsWith("<") || !uri.endsWith(">")) {
            throw new IllegalArgumentException("Link was not a valid link");
        }

        Map<String, String> parma = h.parameters();
        Link.Builder builder = new MuLinkBuilder()
            .uri(uri.substring(1, uri.length() - 1))
            .rel(parma.get("rel"))
            .title(parma.get("title"))
            .type(parma.get("type"));

        for (Map.Entry<String, String> entry : parma.entrySet()) {
            String key = entry.getKey();
            if ("rel".equals(key) || "title".equals(key) || "type".equals(key)) {
                continue;
            }
            builder.param(key, entry.getValue());
        }

        return builder.build();
    }

    @Override
    public String toString(Link value) {
        StringBuilder sb = new StringBuilder();
        sb.append("<").append(value.getUri().toString()).append(">");
        if (value.getRels().size() > 0) {
            sb.append("; " + "rel" + "=\"").append(String.join(" ", value.getRels())).append("\"");
        }
        app(sb, "title", value.getTitle());
        app(sb, "type", value.getType());

        for (Map.Entry<String, String> entry : value.getParams().entrySet()) {
            app(sb, entry.getKey(), entry.getValue());
        }

        return sb.toString();
    }

    private static void app(StringBuilder sb, String key, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append("; ").append(key).append("=\"").append(value.replace("\"", "\\\"")).append("\"");
        }
    }


    static class MuLink extends Link {

        private final URI uri;
        private final List<String> rels;
        private final @Nullable String title;
        private final @Nullable String type;
        private final Map<String, String> params;

        MuLink(URI uri, List<String> rels, @Nullable String title, @Nullable String type, Map<String, String> params) {
            Mutils.notNull("uri", uri);
            this.uri = java.util.Objects.requireNonNull(uri);
            Mutils.notNull("rels", rels);
            this.rels = java.util.Objects.requireNonNull(rels);
            this.title = title;
            this.type = type;
            Mutils.notNull("params", params);
            this.params = java.util.Objects.requireNonNull(params);
        }

        @Override
        public URI getUri() {
            return uri;
        }

        @Override
        public UriBuilder getUriBuilder() {
            return UriBuilder.fromUri(uri);
        }

        @Override
        public @Nullable String getRel() {
            return rels.isEmpty() ? null : rels.get(0);
        }

        @Override
        public List<String> getRels() {
            return rels;
        }

        @Override
        public @Nullable String getTitle() {
            return title;
        }

        @Override
        public @Nullable String getType() {
            return type;
        }

        @Override
        public Map<String, String> getParams() {
            return params;
        }

        @Override
        public String toString() {
            return HEADER_DELEGATE.toString(this);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MuLink muLink = (MuLink) o;
            return uri.equals(muLink.uri) &&
                Objects.equals(rels, muLink.rels) &&
                Objects.equals(title, muLink.title) &&
                Objects.equals(type, muLink.type) &&
                Objects.equals(params, muLink.params);
        }

        @Override
        public int hashCode() {
            return Objects.hash(uri, rels, title, type, params);
        }
    }

    static class MuLinkBuilder implements Link.Builder {

        private @Nullable UriBuilder uri;
        private @Nullable List<String> rels;
        private @Nullable String title;
        private @Nullable String type;
        private @Nullable Map<String, String> params;
        private @Nullable URI baseUri;

        @Override
        public Link.Builder link(Link link) {
            this.uri = link.getUriBuilder();
            this.rels = link.getRels();
            this.title = link.getTitle();
            this.type = link.getType();
            this.params = link.getParams();
            return this;
        }

        @Override
        public Link.Builder link(String link) {
            return this.link(HEADER_DELEGATE.fromString(link));
        }

        @Override
        public Link.Builder uri(URI uri) {
            this.uri = UriBuilder.fromUri(uri);
            return this;
        }

        @Override
        public Link.Builder uri(String uri) {
            this.uri = UriBuilder.fromUri(uri);
            return this;
        }

        @Override
        public Link.Builder baseUri(URI uri) {
            this.baseUri = uri;
            return this;
        }

        @Override
        public Link.Builder baseUri(String uri) {
            return baseUri(URI.create(uri));
        }

        @Override
        public Link.Builder uriBuilder(UriBuilder uriBuilder) {
            this.uri = uriBuilder;
            return this;
        }

        @Override
        public Link.Builder rel(String rel) {
            if (rel == null || rel.isEmpty()) {
                return this;
            }
            if (rels == null) {
                rels = new ArrayList<>();
            }
            rels.add(rel);
            return this;
        }

        @Override
        public Link.Builder title(String title) {
            this.title = title;
            return this;
        }

        @Override
        public Link.Builder type(String type) {
            this.type = type;
            return this;
        }

        @Override
        public Link.Builder param(String name, String value) {
            if (params == null) {
                params = new HashMap<>();
            }
            params.put(name, value);
            return this;
        }

        @Override
        public Link build(Object... values) {
            return buildRelativized(null, values);
        }

        @Override
        public Link buildRelativized(@Nullable URI relativeTo, Object... values) {
            if (this.uri == null) {
                throw new UriBuilderException("No URI has been set");
            }
            URI built = this.uri.build(values);
            if (baseUri != null) {
                built = baseUri.resolve(built);
            }
            if (relativeTo != null) {
                built = relativeTo.relativize(built);
            }
            List<String> relsToUse = rels == null ? emptyList() : rels;
            Map<String, String> paramsToUse = params == null ? emptyMap() : params;
            return new MuLink(built, relsToUse, title, type, paramsToUse);
        }
    }
}
