package io.muserver;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

class FieldLine implements Map.Entry<String,String> {

    private final HeaderString name;
    private final HeaderString value;

    FieldLine(HeaderString name, HeaderString value) {
        this.name = name;
        this.value = value;
    }

    int length() {
        return name.length() + value.length();
    }

    public HeaderString name() {
        return name;
    }

    public HeaderString value() {
        return value;
    }

    @Override
    public String getKey() {
        return name.toString();
    }

    @Override
    public String getValue() {
        return value.toString();
    }

    @Override
    public String setValue(String value) {
        throw new UnsupportedOperationException("Values are readonly");
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FieldLine fieldLine = (FieldLine) o;
        return Objects.equals(name, fieldLine.name) && Objects.equals(value, fieldLine.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value);
    }

    @Override
    public String toString() {
        return "FieldLine{" +
            "name=" + name +
            ", value=" + value +
            '}';
    }

}

