package io.muserver;

import java.util.*;

import static io.muserver.NettyRequestParameters.isTruthy;

class CaseSensitiveRequestParameters implements RequestParameters {

    private final Map<String, List<String>> all = new HashMap<>();

    @Override
    public Map<String, List<String>> all() {
        return all;
    }

    public void add(String name, String value) {
        List<String> cur = all.get(name);
        if (cur == null || cur.isEmpty()) {
            ArrayList<String> theList = new ArrayList<>(1);
            theList.add(value);
            all.put(name, theList);
        } else {
            cur.add(value);
        }
    }

    @Override
    public String get(String name) {
        return get(name, null);
    }

    @Override
    public String get(String name, String defaultValue) {
        List<String> all = getAll(name);
        if (all.isEmpty()) {
            return defaultValue;
        }
        return all.get(0);
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
        List<String> vals = all.get(name);
        return vals == null ? Collections.emptyList() : vals;
    }

    @Override
    public boolean contains(String name) {
        return all.containsKey(name);
    }

    @Override
    public String toString() {
        return all.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CaseSensitiveRequestParameters that = (CaseSensitiveRequestParameters) o;
        return Objects.equals(all, that.all);
    }

    @Override
    public int hashCode() {
        return Objects.hash(all);
    }
}
