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

    /**
     * Gets the parameter as an integer, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as an integer.
     */
    int getInt(String name, int defaultValue);


    /**
     * Gets the parameter as a long, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as a long.
     */
    long getLong(String name, long defaultValue);


    /**
     * Gets the parameter as a float, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as a float.
     */
    float getFloat(String name, float defaultValue);

    /**
     * Gets the parameter as a double, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as a double.
     */
    double getDouble(String name, double defaultValue);

    /**
     * <p>Gets a parameter as a boolean, where values such as <code>true</code>, <code>on</code> and <code>yes</code> as
     * considered true, and other values (or no parameter with the name) is considered false.</p>
     * <p>This can be used to access checkbox values as booleans.</p>
     * @param name The name of the parameter.
     * @return Returns true if the value was truthy, or false if it was falsy or not specified.
     */
    boolean getBoolean(String name);

    /**
     * Gets all the querystring parameters with the given name, or an empty list if none are found.
     *
     * @param name The querystring parameter name to get
     * @return All values of the parameter with the given name
     */
    List<String> getAll(String name);

    /**
     * Returns true if the given parameter is specified with any value
     * @param name The name of the value
     * @return True if it's specified; otherwise false.
     */
    boolean contains(String name);
}

