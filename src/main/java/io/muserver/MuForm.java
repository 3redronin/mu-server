package io.muserver;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;

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

