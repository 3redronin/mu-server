package io.muserver;

import java.util.Map;

public class HeadersFactory {

    public static Headers create(Map<String,Object> values) {
        Headers headers = Headers.create();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            headers.add(entry.getKey(), entry.getValue());
        }
        return headers;
    }
}
