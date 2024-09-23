package io.muserver;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;

class UrlEncodedMuForm implements MuForm {
    private final QueryString qs;

    UrlEncodedMuForm(QueryString qs) {
        this.qs = qs;
    }

    @Override
    public RequestParameters params() {
        return qs;
    }

    @Override
    public Map<String, List<UploadedFile>> uploadedFiles() {
        return emptyMap();
    }

    @Override
    public Map<String, List<String>> all() {
        return qs.all();
    }

    public static UrlEncodedMuForm parse(@NotNull String text) {
        return new UrlEncodedMuForm(QueryString.parse(text));
    }
}
