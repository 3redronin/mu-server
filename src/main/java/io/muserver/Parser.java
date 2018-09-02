package io.muserver;

class Parser {
}

class FieldNameParser {

    static boolean add(StringBuilder buffer, byte c) throws InvalidRequestException {
        if (c == ':') {
            if (buffer.length() == 0) {
                throw new InvalidRequestException(400, "Invalid HTTP Message", "A field name was empty");
            }
            return true;
        }
        if (!isTChar(c)) {
            throw new InvalidRequestException(400, "Invalid character in request", "Ascii code " + c + " was in a field name");
        }
        buffer.append((char)c);
        return false;
    }

    static boolean isTChar(byte c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9' || c == '!' ||
            c == '#' || c == '$' || c == '%' || c == '&' || c == '\'' || c == '*' || c == '+' ||
            c == '-' || c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~');
    }
}

class FieldValueParser {

    static boolean add(StringBuilder buffer, byte c) throws InvalidRequestException {
        if (c == ':') {
            if (buffer.length() == 0) {
                throw new InvalidRequestException(400, "Invalid HTTP Message", "A field name was empty");
            }
            return true;
        }
        if (!FieldNameParser.isTChar(c)) {
            throw new InvalidRequestException(400, "Invalid character in request", "Ascii code " + c + " was in a field name");
        }
        buffer.append((char)c);
        return false;
    }

    static boolean isFieldVChar(byte c) {
        return FieldNameParser.isTChar(c);
    }

    static boolean isQDTest(byte c) {
        return (c >= 0x23 && c<= 0x5B) || (c >= 0x5D && c <= 0x7E) || c == '\t' || c == ' ' || c == 0x21;
    }

}
