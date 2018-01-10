package io.muserver.rest;

import org.hamcrest.MatcherAssert;
import org.junit.Test;

import javax.ws.rs.core.MediaType;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class MediaTypeHeaderDelegateTest {
    private final MediaTypeHeaderDelegate delegate = new MediaTypeHeaderDelegate();

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
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("image/jpeg"), types("*/*")), is(true));
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("image/*", "text/plain"), types("*/*")), is(true));
    }

    @Test
    public void ifClientAcceptsSubTypeWildcardsThenThoseCanBeServiced() {
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("image/jpeg"), types("image/*")), is(true));
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("image/*", "text/plain"), types("image/*")), is(true));
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("text/*"), types("image/*")), is(false));
    }

    @Test
    public void wildcardsCannotProvideForSpecificTypes() {
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("*/*"), types("image/svg+xml", "image/jpeg")), is(true));
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("image/*"), types("image/svg+xml", "image/jpeg")), is(true));
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("image/*"), types("*/*")), is(true));
        MatcherAssert.assertThat(MediaTypeHeaderDelegate.atLeastOneCompatible(types("image/*"), types("text/*")), is(false));
    }


}