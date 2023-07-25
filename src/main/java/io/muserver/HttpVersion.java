package io.muserver;

public enum HttpVersion {

    HTTP_1_0("HTTP/1.0"), HTTP_1_1("HTTP/1.1");

    private final String version;

    HttpVersion(String version) {
        this.version = version;
    }

    public String version() {
        return version;
    }

    public static HttpVersion fromRequestLine(String val) {
        switch (val) {
            case "HTTP/1.1":
                return HTTP_1_1;
            case "HTTP/1.0":
                return HTTP_1_0;
            default:
                return null;
        }
    }
}
