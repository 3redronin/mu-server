package io.muserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;

/**
 * {@inheritDoc}
 */
class MultipartForm implements MuForm {

    private Map<String, List<String>> params;
    private Map<String, List<UploadedFile>> fileParams;

    void addFile(String name, UploadedFile file) {
        if (fileParams == null) {
            fileParams = new HashMap<>();
        }
        List<UploadedFile> files = fileParams.computeIfAbsent(name, k -> new ArrayList<>());
        files.add(file);
    }

    void addValue(String name, String value) {
        if (params == null) {
            params = new HashMap<>();
        }
        List<String> values = params.computeIfAbsent(name, k -> new ArrayList<>());
        values.add(value);
    }

    @Override
    public RequestParameters params() {
        return this;
    }

    @Override
    public Map<String, List<UploadedFile>> uploadedFiles() {
        return fileParams == null ? emptyMap() : fileParams;
    }

    @Override
    public Map<String, List<String>> all() {
        return params == null ? emptyMap() : params;
    }

}
