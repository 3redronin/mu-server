package io.muserver;

import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;

class NettyRequestParameters implements RequestParameters {

    private final QueryStringDecoder queryStringDecoder;

    NettyRequestParameters(QueryStringDecoder queryStringDecoder) {
        Mutils.notNull("queryStringDecoder", queryStringDecoder);
        this.queryStringDecoder = queryStringDecoder;
    }

    @Override
    public Map<String, List<String>> all() {
        return queryStringDecoder.parameters();
    }

    @Override
    public String get(String name) {
        return get(name, "");
    }

    @Override
    public String get(String name, String defaultValue) {
        List<String> values = queryStringDecoder.parameters().get(name);
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
        List<String> values = queryStringDecoder.parameters().get(name);
        if (values == null) {
            return emptyList();
        }
        return values;
    }
}
