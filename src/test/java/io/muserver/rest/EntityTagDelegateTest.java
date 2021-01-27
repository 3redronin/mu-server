package io.muserver.rest;

import org.junit.Test;

import javax.ws.rs.core.EntityTag;
import javax.ws.rs.ext.RuntimeDelegate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class EntityTagDelegateTest {

    private final RuntimeDelegate.HeaderDelegate<EntityTag> del = MuRuntimeDelegate.ensureSet().createHeaderDelegate(EntityTag.class);

    @Test
    public void strongTagsSupported() {
        EntityTag tag = del.fromString("\"33a64df551425fcc55e4d42a148795d9f25f89d4\"");
        assertThat(tag.isWeak(), is(false));
        assertThat(tag.getValue(), is("33a64df551425fcc55e4d42a148795d9f25f89d4"));
        assertThat(tag.toString(), is("\"33a64df551425fcc55e4d42a148795d9f25f89d4\""));
    }

    @Test
    public void weakTagsSupported() {
        EntityTag tag = del.fromString("W/\"33a64df551425fcc55e4d42a148795d9f25f89d4\"");
        assertThat(tag.isWeak(), is(true));
        assertThat(tag.getValue(), is("33a64df551425fcc55e4d42a148795d9f25f89d4"));
        assertThat(tag.toString(), is("W/\"33a64df551425fcc55e4d42a148795d9f25f89d4\""));
    }

    @Test(expected = IllegalArgumentException.class)
    public void badOnesThrow() {
        del.fromString("W/33a64df551425fcc5\"");
    }

    @Test
    public void canHandleSpecialCharacters() {
        EntityTag tag = del.fromString("W/\"lkajsd\\\"fkljsklfdj\"");
        assertThat(tag.isWeak(), is(true));
        assertThat(tag.getValue(), is("lkajsd\"fkljsklfdj"));
        assertThat(tag.toString(), is("W/\"lkajsd\\\"fkljsklfdj\""));
    }

}