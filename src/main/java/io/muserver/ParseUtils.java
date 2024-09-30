package io.muserver;

class ParseUtils {
    static boolean isTChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9' || c == '!' ||
            c == '#' || c == '$' || c == '%' || c == '&' || c == '\'' || c == '*' || c == '+' ||
            c == '-' || c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~');
    }

    static boolean isTChar(byte b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9' || b == '!' ||
            b == '#' || b == '$' || b == '%' || b == '&' || b == '\'' || b == '*' || b == '+' ||
            b == '-' || b == '.' || b == '^' || b == '_' || b == '`' || b == '|' || b == '~');
    }


    static boolean isVChar(char c) {
        return c >= 0x21 && c <= 0x7E;
    }
    static boolean isVChar(byte c) {
        return c >= 0x21 && c <= 0x7E;
    }

    static boolean isOWS(char c) {
        return c == ' ' || c == '\t';
    }
    static boolean isOWS(byte c) {
        return c == ' ' || c == '\t';
    }

    static String quoteIfNeeded(String value) {
        boolean needsQuoting = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isTChar(c)) {
                needsQuoting = true;
                break;
            }
        }
        return needsQuoting ? '"' + value.replace("\"", "\\\"") + '"' : value;
    }
}
