package io.muserver;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;

class EmptyForm implements MuForm {

    static final MuForm VALUE = new EmptyForm();

    private EmptyForm() {
    }

    @Override
    public RequestParameters params() {
        return this;
    }

    @Override
    public Map<String, List<UploadedFile>> uploadedFiles() {
        return emptyMap();
    }

    @Override
    public Map<String, List<String>> all() {
        return emptyMap();
    }

}
