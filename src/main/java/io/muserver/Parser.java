package io.muserver;

class Parser {
    static boolean isTChar(byte c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9' || c == '!' ||
            c == '#' || c == '$' || c == '%' || c == '&' || c == '\'' || c == '*' || c == '+' ||
            c == '-' || c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~');
    }
    static boolean isOWS(byte c) {
        return c == ' ' || c == '\t';
    }
    static boolean isTChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9' || c == '!' ||
            c == '#' || c == '$' || c == '%' || c == '&' || c == '\'' || c == '*' || c == '+' ||
            c == '-' || c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~');
    }
    static boolean isVChar(byte c) {
        return c >= 0x21 && c <= 0x7E;
    }
    static boolean isVChar(char c) {
        return c >= 0x21 && c <= 0x7E;
    }
    static boolean isOWS(char c) {
        return c == ' ' || c == '\t';
    }
}
