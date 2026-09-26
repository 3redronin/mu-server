package io.muserver;

/** A normalized application field name, unlike a raw name decoded from HPACK. */
final class ValidatedHeaderName extends HeaderString {
    private ValidatedHeaderName(String name) {
        super(name);
    }

    static ValidatedHeaderName builtIn(String name) {
        return new ValidatedHeaderName(name);
    }

    static ValidatedHeaderName custom(String name) {
        return new ValidatedHeaderName(name);
    }

    static ValidatedHeaderName from(CharSequence name) {
        return name instanceof ValidatedHeaderName
            ? (ValidatedHeaderName) name
            : (ValidatedHeaderName) HeaderString.valueOf(name, Type.HEADER);
    }
}
