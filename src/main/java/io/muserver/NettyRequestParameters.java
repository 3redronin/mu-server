package io.muserver;

import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.*;

import static java.util.Collections.emptyList;

class NettyRequestParameters implements RequestParameters {

    private final QueryStringDecoder decoder;

    NettyRequestParameters(QueryStringDecoder decoder) {
        Mutils.notNull("decoder", decoder);
        this.decoder = decoder;
    }

    @Override
    public Map<String, List<String>> all() {
        return decoder.parameters();
    }

    @Override
    public String get(String name) {
        return get(name, "");
    }

    @Override
    public String get(String name, String defaultValue) {
        List<String> values = decoder.parameters().get(name);
        if (values == null) {
            return defaultValue;
        }
        return values.get(0);
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

    static boolean isTruthy(String val) {
        switch (val) {
            case "true":
            case "on":
            case "yes":
            case "1":
                return true;
            default:
                return false;
        }
    }

    @Override
    public List<String> getAll(String name) {
        List<String> values = decoder.parameters().get(name);
        if (values == null) {
            return emptyList();
        }
        return values;
    }

    @Override
    public boolean contains(String name) {
        return decoder.parameters().containsKey(name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NettyRequestParameters that = (NettyRequestParameters) o;
        return Objects.equals(decoder.parameters(), that.decoder.parameters());
    }

    @Override
    public int hashCode() {
        return Objects.hash(decoder.parameters());
    }

    @Override
    public String toString() {
        String s = decoder.toString();
        int qm = s.indexOf('?');
        return qm == -1 ? s : s.substring(qm + 1);
    }
}
