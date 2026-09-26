package io.muserver;

/** An application field value that has passed validation and whitespace normalization. */
final class ValidatedHeaderValue extends HeaderString {
    static final ValidatedHeaderValue EMPTY_VALUE = new ValidatedHeaderValue("");

    private ValidatedHeaderValue(String value) {
        super(value);
    }

    static ValidatedHeaderValue normalized(String value) {
        return new ValidatedHeaderValue(value);
    }
}
