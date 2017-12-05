package ronin.muserver;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsNull.nullValue;

public class MuServerTest {

	@Test
	public void blah() {
		assertThat(new MuServer(), is(not(nullValue())));
	}

}