package io.muserver.rest;

import io.muserver.HeaderNames;
import io.muserver.Headers;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import java.util.*;

import static java.util.stream.Collectors.toList;

class LegacyJaxRsHttpHeadersAdapter implements HttpHeaders {
    private static final List<Locale> WILDCARD_LOCALES = Collections.singletonList(new Locale("*"));
    private static final List<MediaType> WILDCARD_MEDIA_TYPES = Collections.singletonList(MediaType.WILDCARD_TYPE);
    private final Headers muHeaders;
    private final List<io.muserver.Cookie> muCookies;
    private MultivaluedMap<String, String> copy;

    LegacyJaxRsHttpHeadersAdapter(Headers headers, List<io.muserver.Cookie> cookies) {
        muHeaders = headers;
        muCookies = cookies;
    }


    @Override
    public List<String> getRequestHeader(String name) {
        return getMutableRequestHeaders().get(name);
    }

    @Override
    public String getHeaderString(String name) {
        List<String> vals = getRequestHeader(name);
        if (vals == null) {
            return null;
        }
        return String.join(",", vals);
    }

    @Override
    public MultivaluedMap<String, String> getRequestHeaders() {
        return LegacyReadOnlyMultivaluedMap.readOnly(getMutableRequestHeaders());
    }

    MultivaluedMap<String, String> getMutableRequestHeaders() {
        if (copy == null) {
            MultivaluedMap<String, String> c = new LegacyLowercasedMultivaluedHashMap<>();
            for (Map.Entry<String, String> entry : muHeaders) {
                c.add(entry.getKey(), entry.getValue());
            }
            copy = c;
        }
        return copy;
    }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
        List<MediaType> mediaTypes = LegacyMediaTypeDeterminer.parseAcceptHeaders(muHeaders.getAll(HeaderNames.ACCEPT));
        if (mediaTypes.isEmpty()) {
            return WILDCARD_MEDIA_TYPES;
        }
        return mediaTypes.stream().sorted(LegacyMediaTypeDeterminer::compareQValues).collect(toList());
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
        try {
            return getLocalesFromHeader(HeaderNames.ACCEPT_LANGUAGE, WILDCARD_LOCALES);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid accept-language header");
        }
    }

    private List<Locale> getLocalesFromHeader(CharSequence headerName, List<Locale> defaultLocales) {
        List<String> all = muHeaders.getAll(headerName);
        if (all.isEmpty()) {
            return defaultLocales;
        }
        return Locale.LanguageRange.parse(String.join(",", all)).stream()
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
        String type = muHeaders.get(HeaderNames.CONTENT_TYPE);
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
        for (io.muserver.Cookie cookie : muCookies) {
            all.put(cookie.name(), new Cookie(cookie.name(), cookie.value()));
        }
        return all;
    }

    @Override
    public Date getDate() {
        long timeMillis = muHeaders.getTimeMillis(HeaderNames.DATE, -1);
        return timeMillis < 0 ? null : new Date(timeMillis);
    }

    @Override
    public int getLength() {
        return muHeaders.getInt(HeaderNames.CONTENT_LENGTH, -1);
    }
}
