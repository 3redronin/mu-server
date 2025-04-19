package io.muserver;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

class ParseUtilsTest {

    @Test
    void tcharsAreValid() {
        var chars = new char[]{'!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~', '0', '9', 'a', 'z', 'A', 'Z'};
        for (char c : chars) {
            assertThat(ParseUtils.isTChar(c), equalTo(true));
            assertThat(ParseUtils.isTChar((byte) c), equalTo(true));
        }
        for (char c = '0'; c <= '9'; c++) {
            assertThat(ParseUtils.isTChar(c), equalTo(true));
            assertThat(ParseUtils.isTChar((byte)c), equalTo(true));
        }

        for (char c = 'a'; c <= 'z'; c++) {
            assertThat(ParseUtils.isTChar(c), equalTo(true));
            assertThat(ParseUtils.isTChar((byte)c), equalTo(true));
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            assertThat(ParseUtils.isTChar(c), equalTo(true));
            assertThat(ParseUtils.isTChar((byte)c), equalTo(true));
        }
        for (char c = 0; c <= 32; c++) {
            assertThat(ParseUtils.isTChar(c), equalTo(false));
            assertThat(ParseUtils.isTChar((byte)c), equalTo(false));
        }
        var nots = new char[] { 34, 40, 41, 44, 47, 58, 59, 60, 61, 62, 63, 64, 91, 92, 93, 123, 125 };
        for (char not : nots) {
            assertThat(ParseUtils.isTChar(not), equalTo(false));
            assertThat(ParseUtils.isTChar((byte)not), equalTo(false));
        }

        for (char i = 127; i <= 256; i++) {
            assertThat(ParseUtils.isTChar(i), equalTo(false));
            assertThat(ParseUtils.isTChar((byte)i), equalTo(false));
        }
    }

}