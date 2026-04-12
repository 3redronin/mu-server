package io.muserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 7541 4.2 Maximum Table Size")
class RFC7541_4_2_MaximumTableSizeTest {

    @Test
    void entriesLargerThanTheMaximumTableSizeEmptyTheDynamicTable() throws Exception {
        HpackTable table = new HpackTable(48);

        FieldLine tooBig = line(HeaderNames.ACCEPT_CHARSET, "This value is definitely too long for a 48 byte table");
        table.indexField(tooBig);

        assertThat(table.dynamicTableSizeInBytes(), equalTo(0));
        assertThrows(Http2Exception.class, () -> table.getValue(62));
    }

    @Test
    void reducingTheMaximumTableSizeEvictsTheOldestEntriesFirst() throws Exception {
        HpackTable table = new HpackTable(4096);

        FieldLine first = line(HeaderNames.ACCEPT_CHARSET, "Some longer thingo");
        FieldLine second = line(HeaderNames.ACCEPT_CHARSET, "Some other thingo!");
        table.indexField(first);
        table.indexField(second);

        assertThat(table.getValue(62), sameInstance(second));
        assertThat(table.getValue(63), sameInstance(first));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(128));

        table.changeMaxSize(100);

        assertThat(table.dynamicTableSizeInBytes(), equalTo(64));
        assertThat(table.getValue(62), sameInstance(second));
        assertThrows(Http2Exception.class, () -> table.getValue(63));
    }

    private static FieldLine line(CharSequence name, String value) {
        return new FieldLine((HeaderString) name, HeaderString.valueOf(value, HeaderString.Type.VALUE));
    }
}

