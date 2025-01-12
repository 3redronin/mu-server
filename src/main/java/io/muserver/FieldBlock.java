package io.muserver;

import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

class FieldBlock implements Headers, Iterable<Map.Entry<String, String>> {

    private final List<FieldLine> lines = new LinkedList<>();

    void add(FieldLine line) {
        lines.add(line);
    }

    void add(int index, FieldLine line) {
        lines.add(index, line);
    }

    @Override
    public @Nullable String get(CharSequence name) {
        var header = HeaderString.valueOf(name, HeaderString.Type.HEADER);
        for (FieldLine line : lines) {
            if (line.name().equals(header)) {
                return line.value().toString();
            }
        }
        return null;
    }

    @Override
    public List<String> getAll(CharSequence name) {
        var header = HeaderString.valueOf(name, HeaderString.Type.HEADER);
        return lines.stream()
            .filter(l -> l.name().equals(header))
            .map(l -> l.value().toString())
            .collect(Collectors.toList());
    }

    public Iterable<FieldLine> lineIterator() {
        return lines;
    }

    public List<Map.Entry<String, String>> entries() {
        return Collections.unmodifiableList(lines);
    }

    @Override
    public boolean contains(CharSequence name) {
        var header = HeaderString.valueOf(name, HeaderString.Type.HEADER);
        for (FieldLine line : lines) {
            if (line.name().equals(header)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
        return new MapEntryStringIterator(lines.iterator());
    }

    @Override
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    @Override
    public int size() {
        return lines.size();
    }

    @Override
    public Set<String> names() {
        return lines.stream().map(line -> line.name().toString()).collect(Collectors.toSet());
    }

    @Override
    public Headers add(CharSequence name, Object value) {
        Objects.requireNonNull(name, "name is null");
        Objects.requireNonNull(value, "value is null");
        return add(HeaderString.valueOf(name, HeaderString.Type.HEADER), value);
    }

    public Headers add(HeaderString name, Object value) {
        lines.add(new FieldLine(name, HeaderString.valueOf(value, HeaderString.Type.VALUE)));
        return this;
    }

    @Override
    public Headers add(CharSequence name, Iterable<?> values) {
        var header = HeaderString.valueOf(name, HeaderString.Type.HEADER);
        for (Object value : values) {
            add(header, value);
        }
        return this;
    }

    @Override
    public Headers add(Headers headers) {
        if (headers instanceof FieldBlock) {
            for (FieldLine line : ((FieldBlock)headers).lines) {
                add(line);
            }
        } else {
            for (Map.Entry<String, String> header : headers) {
                add(header.getKey(), header.getValue());
            }
        }
        return this;
    }

    @Override
    public Headers set(CharSequence name, @Nullable Object value) {
        Objects.requireNonNull(name, "name is null");
        var header = HeaderString.valueOf(name, HeaderString.Type.HEADER);
        if (value == null) {
            remove(header);
        } else {
            FieldLine line = new FieldLine(header, HeaderString.valueOf(value, HeaderString.Type.VALUE));
            remove(line.name());
            add(line);
        }
        return this;
    }

    @Override
    public Headers set(CharSequence name, Iterable<?> values) {
        Objects.requireNonNull(name, "name is null");
        Objects.requireNonNull(values, "values is null");
        var header = HeaderString.valueOf(name, HeaderString.Type.HEADER);
        remove(header);
        for (Object value : values) {
            add(header, value);
        }
        return this;
    }

    @Override
    public Headers set(Headers headers) {
        return clear().add(headers);
    }

    @Override
    public Headers setAll(Headers headers) {
        for (String toOverwrite : headers.names()) {
            remove(toOverwrite);
        }
        return add(headers);
    }

    @Override
    public Headers remove(CharSequence name) {
        Objects.requireNonNull(name, "name is null");
        var header = HeaderString.valueOf(name, HeaderString.Type.HEADER);
        remove(header);
        return this;
    }

    void remove(HeaderString name) {
        lines.removeIf(line -> line.name().equals(name));
    }


    @Override
    public Headers clear() {
        lines.clear();
        return this;
    }

    @Override
    public boolean contains(CharSequence name, CharSequence value, boolean ignoreCase) {
        var header = HeaderString.valueOf(name, HeaderString.Type.HEADER);
        for (FieldLine line : lines) {
            boolean nameMatches = line.name().equals(header);
            if (nameMatches && line.value().contentEquals(value, ignoreCase)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsValue(CharSequence name, CharSequence value, boolean ignoreCase) {
        var header = HeaderString.valueOf(name, HeaderString.Type.HEADER);
        for (FieldLine line : lines) {
            boolean headerMatches = line.name().equals(header);
            if (headerMatches) {
                if (line.value().contentEquals(value, ignoreCase)) {
                    return true;
                } else if (line.value().containsChar((byte)',')) {
                    var bits = line.value().toString().split("\\s*,\\s*");
                    for (String bit : bits) {
                        if (ignoreCase) {
                            if (bit.equalsIgnoreCase(value.toString())) {
                                return true;
                            }
                        } else {
                            if (bit.contentEquals(value)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }


    @Override
    public String toString(@Nullable Collection<String> toSuppress) {
        // TODO keep track of what to suppress based on never-index in the hpack
        var sup = toSuppress == null ? Set.of("authorization", "cookie", "set-cookie") : toSuppress;
        var sb = new StringBuilder("HttpHeaders[");
        var first = true;
        for (var line : lines) {
            if (!first) sb.append(", ");
            var name = line.name().toString();
            var suppress = sup.stream().anyMatch(v -> v.equalsIgnoreCase(name));
            String value = suppress ? "(hidden)" : line.value().toString();
            sb.append(name).append(": ").append(value);
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public String toString() {
        return toString(null);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FieldBlock entries = (FieldBlock) o;
        return Objects.equals(lines, entries.lines);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(lines);
    }

        private static class MapEntryStringIterator implements Iterator<Map.Entry<String, String>> {

        private final Iterator<FieldLine> lineIterator;

        private MapEntryStringIterator(Iterator<FieldLine> lineIterator) {
            this.lineIterator = lineIterator;
        }

        @Override
        public boolean hasNext() {
            return lineIterator.hasNext();
        }

        @Override
        public Map.Entry<String, String> next() {
            return lineIterator.next();
        }

        @Override
        public void remove() {
            lineIterator.remove();
        }
    }

}
