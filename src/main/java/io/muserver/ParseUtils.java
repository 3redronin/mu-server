package io.muserver;

class ParseUtils {
    static boolean isTChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9' || c == '!' ||
            c == '#' || c == '$' || c == '%' || c == '&' || c == '\'' || c == '*' || c == '+' ||
            c == '-' || c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~');
    }

    static boolean isVChar(char c) {
        return c >= 0x21 && c <= 0x7E;
    }

    static boolean isOWS(char c) {
        return c == ' ' || c == '\t';
    }

    static String quoteIfNeeded(String value) {
        boolean needsQuoting = false;
        for (int i = 0; i < value.length(); i++) {
            if (!isTChar(value.charAt(i))) {
                needsQuoting = true;
                break;
            }
        }
        return needsQuoting ? '"' + value.replace("\"", "\\\"") + '"' : value;
    }
}
