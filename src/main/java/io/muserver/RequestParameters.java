package io.muserver;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
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
     * <p>Gets the value with the given name, or null if there is no parameter with that name.</p>
     * <p>If there are multiple parameters with the same name, the first one is returned.</p>
     *
     * @param name The name of the parameter to get
     * @return The value, or null
     */
    default @Nullable String get(String name) {
        return get(name, null);
    }


    /**
     * <p>Gets the value with the given name, or the default value if there is no parameter with that name.</p>
     * <p>If there are multiple parameters with the same name, the first one is returned.</p>
     *
     * @param name The name of the parameter to get
     * @param defaultValue The default value to use if there is no given value
     * @return The value of the parameter, or the default value
     */
    default @Nullable String get(String name, @Nullable String defaultValue) {
        List<String> matches = getAll(name);
        return matches.isEmpty() ? defaultValue : matches.get(0);
    }

    /**
     * Gets the parameter as an integer, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as an integer.
     */
    default int getInt(String name, int defaultValue) {
        try {
            var v = get(name);
            if (v == null) return defaultValue;
            return Integer.parseInt(v, 10);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }


    /**
     * Gets the parameter as a long, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as a long.
     */
    default long getLong(String name, long defaultValue) {
        try {
            var v = get(name);
            if (v == null) return defaultValue;
            return Long.parseLong(v, 10);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }


    /**
     * Gets the parameter as a float, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as a float.
     */
    default float getFloat(String name, float defaultValue) {
        try {
            var v = get(name);
            if (v == null) return defaultValue;
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Gets the parameter as a double, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as a double.
     */
    default double getDouble(String name, double defaultValue) {
        try {
            var v = get(name);
            if (v == null) return defaultValue;
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * <p>Gets a parameter as a boolean, where values such as <code>true</code>, <code>on</code> and <code>yes</code> as
     * considered true, and other values (or no parameter with the name) is considered false.</p>
     * <p>This can be used to access checkbox values as booleans.</p>
     * @param name The name of the parameter.
     * @return Returns true if the value was truthy, or false if it was falsy or not specified.
     */
    default boolean getBoolean(String name) {
        String val = get(name);
        return Mutils.isTruthy(val);
    }

    /**
     * Gets all the parameters with the given name, or an empty list if none are found.
     *
     * @param name The parameter name to get
     * @return All values of the parameter with the given name
     */
    default List<String> getAll(String name) {
        List<String> vals = all().get(name);
        return vals == null ? Collections.emptyList() : vals;
    }

    /**
     * Returns true if the given parameter is specified with any value
     * @param name The name of the value
     * @return True if it's specified; otherwise false.
     */
    default boolean contains(String name) {
        return all().containsKey(name);
    }

    /**
     * @return True if there are no values
     */
    default boolean isEmpty() {
        return size() == 0;
    }

    /**
     * @return The number of parameters.
     * <p>Where one parameter has multiple values it is only counted once.</p>
     */
    default int size() {
        return all().size();
    }
}

