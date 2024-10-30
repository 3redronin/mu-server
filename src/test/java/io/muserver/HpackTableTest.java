package io.muserver;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HpackTableTest {

    private final HpackTable table = new HpackTable(4096);

    @Test
    void thingsOnTheEdgesWork() throws Http2Exception {
        assertThat(table.getValue(1).name(), sameInstance(HeaderNames.PSEUDO_AUTHORITY));
        assertThat(table.getValue(61).name(), sameInstance(HeaderNames.WWW_AUTHENTICATE));
    }

    @Test
    void outsideItsJurisdictionItThrows() {
        assertThrows(Http2Exception.class, () -> table.getValue(0));
        assertThrows(Http2Exception.class, () -> table.getValue(62));
    }

    @Test
    void theDynamicQueueIsFifo() throws Http2Exception {
        FieldLine thing1 = line(HeaderNames.ACCEPT_CHARSET, "Some longer thingo");
        table.indexField(thing1);
        assertThat(table.dynamicTableSizeInBytes(), equalTo(64));
        assertThat(table.getValue(62), sameInstance(thing1));

        FieldLine otherThing = line(HeaderNames.ACCEPT_CHARSET, "Some other thingo!");
        table.indexField(otherThing);
        assertThat(table.dynamicTableSizeInBytes(), equalTo(128));

        assertThat(table.getValue(62), sameInstance(otherThing));
        assertThat(table.getValue(63), sameInstance(thing1));

        table.changeMaxSize(100);
        assertThat(table.dynamicTableSizeInBytes(), equalTo(64));
        assertThat(table.getValue(62), sameInstance(otherThing));

        FieldLine thing3 = line(HeaderNames.ACCEPT_CHARSET, "neww other thingo!");
        table.indexField(thing3);
        assertThat(table.getValue(62), sameInstance(thing3));

    }

    @NotNull
    private static FieldLine line(CharSequence name, String value) {
        return new FieldLine((HeaderString) name, HeaderString.valueOf(value, HeaderString.Type.VALUE));
    }


}