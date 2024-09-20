package io.muserver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

public class TokenListHeaderTest {

    @Test
    public void emptyStringsAreEmptyLists() {
        var empty = TokenListHeader.parse(emptyList(), true);
        assertThat(empty.tokens(), equalTo(emptyList()));
        assertThat(TokenListHeader.parse(null, true), equalTo(empty));
        assertThat(TokenListHeader.parse(List.of("", " ", "\t", ",", " , "), false), equalTo(empty));
        assertThat(TokenListHeader.parse(List.of("", " ", "\t", ",", " , "), true), equalTo(empty));
    }

    @Test
    public void tokensAreParsedAsCSVs() {
        var created = TokenListHeader.parse(List.of("*", "connection , vary   \t ,,,,", ",vary"), true);
        assertThat(created.tokens(), contains("*", "connection", "vary", "vary"));
    }

    @Test
    public void duplicatesCanBeDiscarded() {
        var created = TokenListHeader.parse(List.of("*", "connection , vary   \t ,,,,", ",vary", " good ,connection", "stuff"), false);
        assertThat(created.tokens(), contains("*", "connection", "vary", "good", "stuff"));
        assertThat(created.toString(), equalTo("*, connection, vary, good, stuff"));
    }

    @Test
    public void tokensCanBeAdded() {
        var value = TokenListHeader.parse(List.of("connection"), true);
        assertThat(value.addIfMissing("connection", true), equalTo(false));
        assertThat(value.tokens(), contains("connection"));
        assertThat(value.addIfMissing("Connection", true), equalTo(false));
        assertThat(value.tokens(), contains("connection"));
        assertThat(value.addIfMissing("Connection", false), equalTo(true));
        assertThat(value.tokens(), contains("connection", "Connection"));
    }

}