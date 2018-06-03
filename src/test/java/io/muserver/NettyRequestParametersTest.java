package io.muserver;

import io.netty.handler.codec.http.QueryStringDecoder;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class NettyRequestParametersTest {

    @Test
    public void canGetSingleStrings() {
        NettyRequestParameters params = create("name=Frank%20Manger&age=10&car=Holden&car=Ford%20Escort");
        assertThat(params.get("name"), equalTo("Frank Manger"));
        assertThat(params.get("age"), equalTo("10"));
        assertThat(params.get("car"), equalTo("Holden"));
        assertThat(params.get("name", "ignored"), equalTo("Frank Manger"));
        assertThat(params.get("notThere"), equalTo(""));
        assertThat(params.get("notThere", "Horza Culture"), equalTo("Horza Culture"));
    }

    @Test
    public void canGetAllParams() {
        NettyRequestParameters params = create("name=Frank%20Manger&age=10&car=Holden&car=Ford%20Escort");
        Map<String, List<String>> all = params.all();
        assertThat(all.size(), is(3));
        assertThat(all.get("name"), is(singletonList("Frank Manger")));
        assertThat(all.get("age"), is(singletonList("10")));
        assertThat(all.get("car"), is(asList("Holden", "Ford Escort")));
    }

    @Test
    public void canGetMultipleStrings() {
        NettyRequestParameters params = create("name=Frank%20Manger&age=10&car=Holden&car=Ford%20Escort");
        assertThat(params.getAll("car"), equalTo(asList("Holden", "Ford Escort")));
        assertThat(params.getAll("name"), equalTo(singletonList("Frank Manger")));
        assertThat(params.getAll("notThere"), equalTo(emptyList()));
    }

    @Test
    public void canGetInts() {
        NettyRequestParameters params = create("name=Frank%20Manger&age=10&car=Holden&car=Ford%20Escort&long=1234567890123456789");
        assertThat(params.getInt("age", -1), is(10));
        assertThat(params.getInt("notThere", -1), is(-1));
        assertThat(params.getInt("name", -1), is(-1));
        assertThat(params.getInt("long", -1), is(-1));
    }

    @Test
    public void canGetLongs() {
        NettyRequestParameters params = create("name=Frank%20Manger&age=1234567890123456789&car=Holden&car=Ford%20Escort");
        assertThat(params.getLong("age", -1), is(1234567890123456789L));
        assertThat(params.getLong("notThere", -1), is(-1L));
        assertThat(params.getLong("name", -1), is(-1L));
    }

    @Test
    public void canGetFloats() {
        NettyRequestParameters params = create("int=10&string=hello&num=123.456");
        assertThat(params.getFloat("num", Float.MIN_VALUE), is(123.456f));
        assertThat(params.getFloat("int", Float.MIN_VALUE), is(10.0f));
        assertThat(params.getFloat("notThere", Float.MIN_VALUE), is(Float.MIN_VALUE));
        assertThat(params.getFloat("string", Float.MIN_VALUE), is(Float.MIN_VALUE));
    }
    
    @Test
    public void canGetDoubles() {
        NettyRequestParameters params = create("int=10&string=hello&num=123.456");
        assertThat(params.getDouble("num", Double.MIN_VALUE), is(123.456));
        assertThat(params.getDouble("int", Double.MIN_VALUE), is(10.0));
        assertThat(params.getDouble("notThere", Double.MIN_VALUE), is(Double.MIN_VALUE));
        assertThat(params.getDouble("string", Double.MIN_VALUE), is(Double.MIN_VALUE));
    }

    @Test
    public void booleansAreTrueForCertainStrings() {
        NettyRequestParameters params = create("good=yes&bad=no&okay=true&radio=on&tv=off");
        assertThat(params.getBoolean("good"), is(true));
        assertThat(params.getBoolean("bad"), is(false));
        assertThat(params.getBoolean("okay"), is(true));
        assertThat(params.getBoolean("radio"), is(true));
        assertThat(params.getBoolean("tv"), is(false));
        assertThat(params.getBoolean("notThere"), is(false));
    }

    private static NettyRequestParameters create(String qs) {
        return new NettyRequestParameters(new QueryStringDecoder(qs, false));
    }

}