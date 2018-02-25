package io.muserver.rest;

import io.muserver.HeaderNames;
import io.muserver.MuRequest;

import javax.ws.rs.core.*;
import java.util.*;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

class JaxRsHttpHeadersAdapter implements HttpHeaders {
    private static final List<Locale> WILDCARD_LOCALES = Collections.unmodifiableList(Collections.singletonList(new Locale("*")));
    private static final List<MediaType> WILDCARD_MEDIA_TYPES = Collections.unmodifiableList(Collections.singletonList(MediaType.WILDCARD_TYPE));
    private final MuRequest request;
    private MultivaluedMap<String, String> copy;

    JaxRsHttpHeadersAdapter(MuRequest request) {
        this.request = request;
    }


    @Override
    public List<String> getRequestHeader(String name) {
        return getRequestHeaders().get(name);
    }

    @Override
    public String getHeaderString(String name) {
        List<String> vals = getRequestHeader(name);
        if (vals == null) {
            return null;
        }
        return vals.stream().collect(joining(","));
    }

    @Override
    public MultivaluedMap<String, String> getRequestHeaders() {
        if (copy == null) {
            MultivaluedMap<String, String> c = new MultivaluedHashMap<>();
            for (Map.Entry<String, String> entry : request.headers()) {
                c.add(entry.getKey(), entry.getValue());
            }
            copy = ReadOnlyMultivaluedMap.readOnly(c);
        }
        return copy;
    }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
        List<MediaType> mediaTypes = MediaTypeDeterminer.parseAcceptHeaders(request.headers().getAll(HeaderNames.ACCEPT));
        return mediaTypes.isEmpty() ? WILDCARD_MEDIA_TYPES : mediaTypes;
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
        return getLocalesFromHeader(HeaderNames.ACCEPT_LANGUAGE, WILDCARD_LOCALES);
    }

    private List<Locale> getLocalesFromHeader(CharSequence headerName, List<Locale> defaultLocales) {
        List<String> all = request.headers().getAll(headerName);
        if (all.isEmpty()) {
            return defaultLocales;
        }
        return Locale.LanguageRange.parse(all.stream().collect(joining(","))).stream()
            .map(lr -> {
                String[] range = lr.getRange().split("-");
                switch (range.length) {
                    case 3:
                        return new Locale(range[0], range[1], range[2]);
                    case 2:
                        return new Locale(range[0], range[1]);
                    case 1:
                        return new Locale(range[0]);
                }
                return null;
            })
            .filter(Objects::nonNull)
            .collect(toList());
    }

    @Override
    public MediaType getMediaType() {
        String type = request.headers().get(HeaderNames.CONTENT_TYPE);
        return type == null ? null : MediaType.valueOf(type);
    }

    @Override
    public Locale getLanguage() {
        List<Locale> localesFromHeader = getLocalesFromHeader(HeaderNames.CONTENT_LANGUAGE, null);
        return localesFromHeader == null || localesFromHeader.isEmpty() ? null : localesFromHeader.get(0);
    }

    @Override
    public Map<String, Cookie> getCookies() {
        Map<String, Cookie> all = new HashMap<>();
        for (io.muserver.Cookie cookie : request.cookies()) {
            all.put(cookie.name(), new Cookie(cookie.name(), cookie.value()));
        }
        return all;
    }

    @Override
    public Date getDate() {
        long timeMillis = request.headers().getTimeMillis(HeaderNames.DATE, -1);
        return timeMillis < 0 ? null : new Date(timeMillis);
    }

    @Override
    public int getLength() {
        return request.headers().getInt(HeaderNames.CONTENT_LENGTH, -1);
    }
}
