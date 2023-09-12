package io.muserver;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

/**
 * The data posted in an HTML form submission
 */
public interface MuForm extends RequestParameters {

    /**
     * The text parameters in the form.
     * @return the text parameters sent in the form
     */
    RequestParameters params();

    /**
     * Gets all uploaded files in the form
     * @return The uploaded files in this form request
     */
    Map<String, List<UploadedFile>> uploadedFiles();

    /**
     * Gets all the uploaded files with the given name, or an empty list if none are found.
     *
     * @param name The file input name to get
     * @return All the files with the given name
     */
    default List<UploadedFile> uploadedFiles(String name) {
        List<UploadedFile> list = uploadedFiles().get(name);
        return list == null ? emptyList() : list;
    };

    /**
     * <p>Gets the uploaded file with the given name, or null if there is no upload with that name.</p>
     * <p>If there are multiple files with the same name, the first one is returned.</p>
     *
     * @param name The querystring parameter name to get
     * @return The querystring value, or an empty string
     */
    default UploadedFile uploadedFile(String name) {
        var list = uploadedFiles().get(name);
        return list == null || list.isEmpty() ? null : list.get(0);
    }


}

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
}

class EmptyForm implements MuForm {

    static final MuForm VALUE = new EmptyForm();
    private EmptyForm() {}

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

class UrlEncodedFormReader implements RequestBodyListener {

    // todo make a proper parser so we don't need to build the string first
    private final StringBuilder sb = new StringBuilder();
    private final FormConsumer formConsumer;

    UrlEncodedFormReader(FormConsumer formConsumer) {
        this.formConsumer = formConsumer;
    }

    @Override
    public void onDataReceived(ByteBuffer buffer, DoneCallback doneCallback) throws Exception {
        // url form encoded is ascii, so no need to worry about boundaries
        CharBuffer decoded = StandardCharsets.US_ASCII.decode(buffer);
        sb.append(decoded);
        doneCallback.onComplete(null);
    }

    @Override
    public void onComplete() {
        var result = QueryString.parse(sb.toString());
        sb.setLength(0);
        try {
            formConsumer.onReady(new UrlEncodedMuForm(result));
        } catch (Exception e) {
            onError(e);
        }
    }

    @Override
    public void onError(Throwable t) {
        formConsumer.onError(t);
    }

}
