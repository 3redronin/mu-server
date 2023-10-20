package io.muserver.rest;

import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;

import javax.ws.rs.core.MediaType;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MediaTypeHeaderDelegateTest {
    private final MediaTypeHeaderDelegate delegate = new MediaTypeHeaderDelegate();

    @Test
    public void throwsIllegalArgumentExceptionIfInvalid() {
        assertThrows(IllegalArgumentException.class, () -> mt("text"));
    }

    @Test
    public void canParse() {
        assertThat(mt("image/jpeg"), equalTo(new MediaType("image", "jpeg")));
        assertThat(mt("image/jpeg ; q=0.5"), equalTo(new MediaType("image", "jpeg", singletonMap("q", "0.5"))));
    }

    @Test
    public void canRoundTrip() {
        assertThat(delegate.toString(mt("image/jpeg")), equalTo("image/jpeg"));
        assertThat(delegate.toString(mt("image/jpeg ; q=0.75; a=1 ")), equalTo("image/jpeg;a=1;q=0.75"));
    }

    private MediaType mt(String value) {
        return delegate.fromString(value);
    }
    private List<MediaType> types(String... value) {
        return MediaTypeHeaderDelegate.fromStrings(asList(value));
    }

    @Test
    public void canConvertDirtyStringArrayToList() {
        List<MediaType> types = MediaTypeHeaderDelegate.fromStrings(asList("image/jpeg;q=0.8, image/gif ", " image/png"));
        assertThat(types, containsInAnyOrder(
            new MediaType("image", "jpeg", singletonMap("q", "0.8")),
            new MediaType("image", "gif"),
            new MediaType("image", "png")
        ));
    }

    @Test
    public void returnsEmptyListForNull() {
        assertThat(MediaTypeHeaderDelegate.fromStrings(null), hasSize(0));
        assertThat(MediaTypeHeaderDelegate.fromStrings(emptyList()), hasSize(0));
    }

    @Test
    public void ifClientAcceptsWildcardThenAnythingGoes() {
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("image/jpeg"), types("*/*"), null), is(true));
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("image/*", "text/plain"), types("*/*"), null), is(true));
    }

    @Test
    public void ifClientAcceptsSubTypeWildcardsThenThoseCanBeServiced() {
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("image/jpeg"), types("image/*"), null), is(true));
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("image/*", "text/plain"), types("image/*"), null), is(true));
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("text/*"), types("image/*"), null), is(false));
    }

    @Test
    public void wildcardsCannotProvideForSpecificTypes() {
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("*/*"), types("image/svg+xml", "image/jpeg"), null), is(true));
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("image/*"), types("image/svg+xml", "image/jpeg"), null), is(true));
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("image/*"), types("*/*"), null), is(true));
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("image/*"), types("text/*"), null), is(false));
    }


}