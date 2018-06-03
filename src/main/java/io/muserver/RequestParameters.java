package io.muserver;

import java.util.List;
import java.util.Map;

/**
 * Provides access to QueryString or Form values.
 */
public interface RequestParameters {

    /**
     * Gets all the parameters as a map
     * @return A map of parameter names to value list
     */
    Map<String, List<String>> all();

    /**
     * <p>Gets the value with the given name, or empty string if there is no parameter with that name.</p>
     * <p>If there are multiple parameters with the same name, the first one is returned.</p>
     *
     * @param name The name of the parameter to get
     * @return The value, or an empty string
     */
    String get(String name);

    /**
     * <p>Gets the value with the given name, or the default value if there is no parameter with that name.</p>
     * <p>If there are multiple parameters with the same name, the first one is returned.</p>
     *
     * @param name The name of the parameter to get
     * @param defaultValue The default value to use if there is no given value
     * @return The value, or an empty string
     */
    String get(String name, String defaultValue);

    int getInt(String name, int defaultValue);
    long getLong(String name, long defaultValue);
    boolean getBoolean(String name);

    float getFloat(String name, float defaultValue);
    double getDouble(String name, double defaultValue);

    /**
     * Gets all the querystring parameters with the given name, or an empty list if none are found.
     *
     * @param name The querystring parameter name to get
     * @return All values of the parameter with the given name
     */
    List<String> getAll(String name);

}

