package io.muserver;

public enum HttpVersion {

    HTTP_1_0("HTTP/1.0"), HTTP_1_1("HTTP/1.1");

    private final String version;

    HttpVersion(String version) {
        this.version = version;
    }

    /**
     * @return The version as a string in the way it appears in the HTTP Protocol, for example <code>HTTP/1.0</code>
     */
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
