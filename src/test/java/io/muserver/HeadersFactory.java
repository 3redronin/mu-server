package io.muserver;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;

import java.util.Map;

public class HeadersFactory {

    public static Headers create() {
        return new Http1Headers();
    }


    public static Headers create(Map<String,Object> values) {
        HttpHeaders entries = new DefaultHttpHeaders();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            entries.add(entry.getKey(), entry.getValue());
        }
        return new Http1Headers(entries);
    }
}
