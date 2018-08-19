package io.muserver;

import java.util.*;

import static io.muserver.NettyRequestParameters.isTruthy;

public class MuHeaders implements RequestParameters {

    private final Map<String, List<String>> all = new HashMap<>();

    @Override
    public Map<String, List<String>> all() {
        return all;
    }

    @Override
    public String get(String name) {
        return get(name, "");
    }

    @Override
    public String get(String name, String defaultValue) {
        List<String> matches = getAll(name);
        return matches.isEmpty() ? defaultValue : matches.get(0);
    }

    @Override
    public int getInt(String name, int defaultValue) {
        try {
            return Integer.parseInt(get(name, ""), 10);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public long getLong(String name, long defaultValue) {
        try {
            return Long.parseLong(get(name, ""), 10);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public float getFloat(String name, float defaultValue) {
        try {
            return Float.parseFloat(get(name, ""));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public double getDouble(String name, double defaultValue) {
        try {
            return Double.parseDouble(get(name, ""));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public boolean getBoolean(String name) {
        String val = get(name, "").toLowerCase();
        return isTruthy(val);
    }

    @Override
    public List<String> getAll(String name) {
        List<String> vals = all.get(name.toLowerCase());
        return vals == null ? Collections.emptyList() : vals;
    }

    @Override
    public boolean contains(String name) {
        return all.containsKey(name.toLowerCase());
    }

    public void put(String header, List<String> values) {
        all.put(header.toLowerCase(), values);
    }

    public int size() {
        return all.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MuHeaders muHeaders = (MuHeaders) o;
        return Objects.equals(all, muHeaders.all);
    }

    @Override
    public int hashCode() {
        return Objects.hash(all);
    }

    @Override
    public String toString() {
        return all.toString();
    }
}
