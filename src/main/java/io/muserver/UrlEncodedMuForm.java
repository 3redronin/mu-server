package io.muserver;

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

    public static UrlEncodedMuForm parse(String text) {
        return new UrlEncodedMuForm(QueryString.parse(text));
    }
}
