package io.muserver.rest;

import org.hamcrest.Matchers;
import org.junit.Test;

import javax.ws.rs.core.MediaType;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

public class CombinedMediaTypeTest {
    private final MediaTypeHeaderDelegate delegate = new MediaTypeHeaderDelegate();

    @Test
    public void defaultsQAndQSValues() {
        CombinedMediaType s = CombinedMediaType.s(delegate.fromString("text/html"), delegate.fromString("text/*"));
        assertThat(s.type, equalTo("text"));
        assertThat(s.subType, equalTo("html"));
        assertThat(s.q, equalTo(1.0));
        assertThat(s.qs, equalTo(1.0));
        assertThat(s.d, equalTo(1));
    }


    @Test
    public void serverEncodingSurvives() {
        CombinedMediaType s = CombinedMediaType.s(delegate.fromString("text/*"), delegate.fromString("text/html; charset=UTF-8"));
        assertThat(s.type, equalTo("text"));
        assertThat(s.subType, equalTo("html"));
    }

    @Test
    public void canTakeQAndQSValues() {
        CombinedMediaType s = CombinedMediaType.s(delegate.fromString("text/*; q=0.5"), delegate.fromString("text/html; qs=0.2"));
        assertThat(s.type, equalTo("text"));
        assertThat(s.subType, equalTo("html"));
        assertThat(s.q, equalTo(0.5));
        assertThat(s.qs, equalTo(0.2));
        assertThat(s.d, equalTo(1));
    }

    @Test
    public void nonMatchesIfMediaTypesIncompatible() {
        CombinedMediaType s = CombinedMediaType.s(delegate.fromString("image/*; q=0.5"), delegate.fromString("text/html; qs=0.2"));
        assertThat(s, sameInstance(CombinedMediaType.NONMATCH));
    }

    @Test
    public void specificIsABetterMatchThanAWildcard() {
        CombinedMediaType[] sample = new CombinedMediaType[]{
            t("*", "*", 0.5, 0.5, 0),
            t("*", "*", 0.3, 0.5, 0),
            t("*", "*", 0.6, 0.5, 0),
            t("*", "*", 0.6, 0.2, 0),
            t("*", "*", 0.6, 1.0, 0),
            t("text", "*", 0.5, 0.5, 0),
            t("text", "*", 0.5, 0.5, 2),
            t("text", "*", 0.5, 0.5, 1),
            t("text", "plain", 0.5, 0.5, 0)
        };
        assertThat(Arrays.stream(sample).sorted(Collections.reverseOrder()).collect(Collectors.toList()), Matchers.contains(
            t("text", "plain", 0.5, 0.5, 0),
            t("text", "*", 0.5, 0.5, 0),
            t("text", "*", 0.5, 0.5, 1),
            t("text", "*", 0.5, 0.5, 2),
            t("*", "*", 0.6, 1.0, 0),
            t("*", "*", 0.6, 0.5, 0),
            t("*", "*", 0.6, 0.2, 0),
            t("*", "*", 0.5, 0.5, 0),
            t("*", "*", 0.3, 0.5, 0)
        ));
    }

    @Test
    public void ifNotCompatibleThenNumbersAreCompared() {
        assertThat(t("text", "html", 1.0, 0.7, 0)
            .compareTo(t("application", "xml", 1.0, 0.2, 0)),
            equalTo(1));
    }

    @Test
    public void itCanCount() {
        assertThat(CombinedMediaType.s(MediaType.valueOf("text/plain"), MediaType.valueOf("*/*")).d, equalTo(2));
        assertThat(CombinedMediaType.s(MediaType.valueOf("text/plain"), MediaType.valueOf("text/plain")).d, equalTo(0));
    }

    private static CombinedMediaType t(String type, String subType, double q, double qs, int d) {
        return new CombinedMediaType(type, subType, q, qs, d, null);
    }


}